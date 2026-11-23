package com.example.network

import android.content.Context
import android.net.Uri
import com.example.audio.EnhancementStrength
import com.example.audio.VoiceEnhanceOutput
import com.example.audio.VoiceEnhanceProgress
import com.example.audio.VoiceEnhanceStage
import com.example.audio.WavUtils
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max

@JsonClass(generateAdapter = true)
data class RemoteVoiceJobCreateResponse(
    val job_id: String
)

@JsonClass(generateAdapter = true)
data class RemoteVoiceJobStatusResponse(
    val job_id: String,
    val status: String,
    val progress: Int = 0,
    val current_chunk: Int = 0,
    val total_chunks: Int = 1,
    val duration_ms: Long = 0L,
    val error: String? = null
)

/**
 * Streaming FastAPI + DeepFilterNet/RNNoise + FFmpeg remote backend client
 * with HTTP Range resumable downloads for long voice recordings.
 */
class RemoteVoiceEnhancerClient(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val createAdapter = moshi.adapter(RemoteVoiceJobCreateResponse::class.java)
    private val statusAdapter = moshi.adapter(RemoteVoiceJobStatusResponse::class.java)

    @Volatile
    private var activeRemoteJobId: String? = null
    @Volatile
    private var activeBaseUrl: String? = null

    suspend fun enhanceRemote(
        localJobId: String,
        baseUrl: String,
        sourceUri: Uri,
        fileName: String,
        fileSizeBytes: Long,
        strength: EnhancementStrength,
        onProgress: (VoiceEnhanceProgress) -> Unit
    ): VoiceEnhanceOutput = withContext(Dispatchers.IO) {
        val cleanBase = normalizeBaseUrl(baseUrl)
        activeBaseUrl = cleanBase

        onProgress(VoiceEnhanceProgress(3, 0, 1, 0L, 0L, VoiceEnhanceStage.PREPARING))

        val streamingBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
            override fun contentLength(): Long = if (fileSizeBytes > 0L) fileSizeBytes else -1L
            override fun writeTo(sink: BufferedSink) {
                val input = context.contentResolver.openInputStream(sourceUri)
                    ?: throw IOException("Unable to read source file")
                input.use { stream ->
                    val buf = ByteArray(65536)
                    var uploaded = 0L
                    while (true) {
                        val read = stream.read(buf)
                        if (read <= 0) break
                        sink.write(buf, 0, read)
                        uploaded += read
                        if (fileSizeBytes > 0L) {
                            val pct = ((uploaded * 22L) / fileSizeBytes).toInt().coerceIn(4, 26)
                            onProgress(
                                VoiceEnhanceProgress(
                                    percent = pct,
                                    currentChunk = 0,
                                    totalChunks = 1,
                                    processedMs = 0L,
                                    totalDurationMs = 0L,
                                    stage = VoiceEnhanceStage.EXTRACTING_AUDIO
                                )
                            )
                        }
                    }
                }
            }
        }

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, streamingBody)
            .addFormDataPart("strength", strength.name.lowercase())
            .addFormDataPart("model", "deepfilternet")
            .build()

        val uploadReq = Request.Builder()
            .url("$cleanBase/api/voice/enhance")
            .post(multipart)
            .build()

        val remoteJobId = client.newCall(uploadReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                if (resp.code == 507 || resp.code == 503) {
                    throw RemoteServerResourceException("Server resources exhausted (HTTP ${resp.code})")
                }
                throw IOException("Voice upload failed (HTTP ${resp.code})")
            }
            val bodyStr = resp.body?.string() ?: throw IOException("Empty server response")
            createAdapter.fromJson(bodyStr)?.job_id
                ?: throw IOException("Invalid job_id from voice server")
        }
        activeRemoteJobId = remoteJobId

        var transientFailures = 0
        var finalDurationMs = 0L
        while (true) {
            coroutineContext.ensureActive()
            val statusReq = Request.Builder()
                .url("$cleanBase/api/voice/jobs/$remoteJobId")
                .get()
                .build()

            val statusResp = try {
                client.newCall(statusReq).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val raw = resp.body?.string() ?: throw IOException("Empty status body")
                    statusAdapter.fromJson(raw) ?: throw IOException("Malformed status JSON")
                }
            } catch (e: IOException) {
                transientFailures++
                if (transientFailures > 5) throw e
                delay(1500L * transientFailures)
                continue
            }

            transientFailures = 0
            finalDurationMs = statusResp.duration_ms

            when (statusResp.status.lowercase()) {
                "completed" -> break
                "failed" -> throw RemoteServerResourceException(
                    statusResp.error ?: "Remote voice enhancement failed"
                )
                "cancelled" -> throw kotlinx.coroutines.CancellationException("Cancelled")
                else -> {
                    val mappedPct = (28 + statusResp.progress * 0.56).toInt().coerceIn(28, 84)
                    onProgress(
                        VoiceEnhanceProgress(
                            percent = mappedPct,
                            currentChunk = statusResp.current_chunk,
                            totalChunks = max(1, statusResp.total_chunks),
                            processedMs = (finalDurationMs * statusResp.progress) / 100L,
                            totalDurationMs = finalDurationMs,
                            stage = VoiceEnhanceStage.DENOISING_AND_DEREVERB
                        )
                    )
                    delay(1200L)
                }
            }
        }

        val outDir = File(context.filesDir, "voice_enhancer/$localJobId").apply { mkdirs() }
        val originalWav = File(outDir, "original.wav")
        val enhancedWav = File(outDir, "enhanced.wav")
        val enhancedMp3 = File(outDir, "enhanced.mp3")

        onProgress(VoiceEnhanceProgress(88, 1, 1, finalDurationMs, finalDurationMs, VoiceEnhanceStage.STUDIO_MASTERING))
        downloadResumable("$cleanBase/api/voice/jobs/$remoteJobId/download/original?format=wav", originalWav)

        onProgress(VoiceEnhanceProgress(93, 1, 1, finalDurationMs, finalDurationMs, VoiceEnhanceStage.FINALIZING))
        downloadResumable("$cleanBase/api/voice/jobs/$remoteJobId/download/enhanced?format=wav", enhancedWav)

        val hasMp3 = runCatching {
            downloadResumable("$cleanBase/api/voice/jobs/$remoteJobId/download/enhanced?format=mp3", enhancedMp3)
            enhancedMp3.length() > 1000L
        }.getOrDefault(false)
        if (!hasMp3) enhancedMp3.delete()

        activeRemoteJobId = null

        val origInfo = inspectWav(originalWav)
        val enhInfo = inspectWav(enhancedWav)

        VoiceEnhanceOutput(
            jobId = localJobId,
            originalWavFile = originalWav,
            enhancedWavFile = enhancedWav,
            durationMs = max(finalDurationMs, enhInfo.durationMs),
            sampleRate = enhInfo.sampleRate,
            channels = enhInfo.channels,
            originalWaveform = origInfo.peaks,
            enhancedWaveform = enhInfo.peaks
        )
    }

    suspend fun cancelActiveJob() = withContext(Dispatchers.IO) {
        val jobId = activeRemoteJobId ?: return@withContext
        val base = activeBaseUrl ?: return@withContext
        runCatching {
            val req = Request.Builder()
                .url("$base/api/voice/jobs/$jobId/cancel")
                .post(RequestBody.create(null, ByteArray(0)))
                .build()
            client.newCall(req).execute().close()
        }
        activeRemoteJobId = null
    }

    /**
     * Supports HTTP Range resume if a long audio download drops mid-stream.
     */
    private fun downloadResumable(url: String, target: File) {
        var attempt = 0
        while (attempt < 4) {
            val existingLen = if (target.exists()) target.length() else 0L
            val reqBuilder = Request.Builder().url(url).get()
            if (existingLen > 0L) {
                reqBuilder.header("Range", "bytes=$existingLen-")
            }
            try {
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.code == 416) return // Already completely downloaded
                    if (!resp.isSuccessful && resp.code != 206) {
                        throw IOException("Download failed: HTTP ${resp.code}")
                    }
                    val append = resp.code == 206 && existingLen > 0L
                    val body = resp.body ?: throw IOException("Empty download body")
                    body.byteStream().use { input ->
                        FileOutputStream(target, append).use { out ->
                            input.copyTo(out, 65536)
                        }
                    }
                }
                return
            } catch (e: IOException) {
                attempt++
                if (attempt >= 4) throw e
            }
        }
    }

    private data class WavInfo(
        val sampleRate: Int,
        val channels: Int,
        val durationMs: Long,
        val peaks: List<Float>
    )

    private fun inspectWav(file: File): WavInfo {
        if (!file.exists() || file.length() <= WavUtils.HEADER_SIZE) {
            return WavInfo(44100, 1, 0L, List(110) { 0.2f })
        }
        val header = ByteArray(WavUtils.HEADER_SIZE)
        file.inputStream().use { it.read(header) }
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val channels = bb.getShort(22).toInt().coerceIn(1, 2)
        val sampleRate = bb.getInt(24).coerceIn(8000, 96000)
        val dataBytes = (file.length() - WavUtils.HEADER_SIZE).coerceAtLeast(0L)
        val bps = sampleRate.toLong() * channels * 2L
        val durationMs = if (bps > 0) (dataBytes * 1000L) / bps else 0L

        val rawPeaks = ArrayList<Float>(120)
        val windowFrames = max(512, sampleRate / 10)
        val buf = ByteArray(windowFrames * channels * 2)
        file.inputStream().use { fis ->
            fis.skip(WavUtils.HEADER_SIZE.toLong())
            while (true) {
                val read = fis.read(buf)
                if (read <= 0) break
                val sb = ByteBuffer.wrap(buf, 0, read).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                var maxAmp = 0f
                while (sb.hasRemaining()) {
                    val v = abs(sb.get() / 32768f)
                    if (v > maxAmp) maxAmp = v
                }
                rawPeaks.add(maxAmp)
            }
        }
        return WavInfo(sampleRate, channels, durationMs, WavUtils.downsamplePeaks(rawPeaks))
    }

    private fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }
}
