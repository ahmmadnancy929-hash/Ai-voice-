package com.example.network

import android.content.Context
import android.net.Uri
import com.example.audio.SeparationOutput
import com.example.audio.SeparationProgress
import com.example.audio.SeparationStage
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
import okio.source
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
data class RemoteCreateJobResponse(
    val job_id: String
)

@JsonClass(generateAdapter = true)
data class RemoteJobStatusResponse(
    val job_id: String,
    val status: String, // queued, processing, completed, failed, cancelled
    val progress: Int = 0,
    val current_chunk: Int = 0,
    val total_chunks: Int = 1,
    val duration_ms: Long = 0L,
    val error: String? = null
)

class RemoteServerResourceException(message: String) : Exception(message)

/**
 * Streaming HTTP client for optional external Python FastAPI + Demucs + FFmpeg backend.
 * Streams large audio files directly from ContentResolver without loading into memory,
 * polls chunked job progress with transient network recovery, and supports cancellation.
 */
class RemoteDemucsClient(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val createAdapter = moshi.adapter(RemoteCreateJobResponse::class.java)
    private val statusAdapter = moshi.adapter(RemoteJobStatusResponse::class.java)

    @Volatile
    private var activeRemoteJobId: String? = null
    @Volatile
    private var activeBaseUrl: String? = null

    suspend fun testConnection(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        val cleanBase = normalizeBaseUrl(baseUrl)
        val req = Request.Builder()
            .url("$cleanBase/health")
            .get()
            .build()
        runCatching {
            client.newCall(req).execute().use { resp -> resp.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun separateRemote(
        localJobId: String,
        baseUrl: String,
        sourceUri: Uri,
        fileName: String,
        fileSizeBytes: Long,
        isolationStrength: Float,
        onProgress: (SeparationProgress) -> Unit
    ): SeparationOutput = withContext(Dispatchers.IO) {
        val cleanBase = normalizeBaseUrl(baseUrl)
        activeBaseUrl = cleanBase

        onProgress(SeparationProgress(3, 0, 1, 0L, 0L, SeparationStage.PREPARING))

        val streamingBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
            override fun contentLength(): Long = if (fileSizeBytes > 0L) fileSizeBytes else -1L
            override fun writeTo(sink: BufferedSink) {
                val input = context.contentResolver.openInputStream(sourceUri)
                    ?: throw IOException("Unable to read source audio file")
                input.use { stream ->
                    val buf = ByteArray(65536)
                    var uploaded = 0L
                    while (true) {
                        val read = stream.read(buf)
                        if (read <= 0) break
                        sink.write(buf, 0, read)
                        uploaded += read
                        if (fileSizeBytes > 0L) {
                            val upPct = ((uploaded * 25L) / fileSizeBytes).toInt().coerceIn(4, 28)
                            onProgress(
                                SeparationProgress(
                                    percent = upPct,
                                    currentChunk = 0,
                                    totalChunks = 1,
                                    processedMs = 0L,
                                    totalDurationMs = 0L,
                                    stage = SeparationStage.SEPARATING
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
            .addFormDataPart("model", "htdemucs")
            .addFormDataPart("strength", isolationStrength.toString())
            .build()

        val uploadReq = Request.Builder()
            .url("$cleanBase/api/separate")
            .post(multipart)
            .build()

        val remoteJobId = client.newCall(uploadReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                if (resp.code == 507 || resp.code == 503) {
                    throw RemoteServerResourceException("Server resources exhausted (HTTP ${resp.code})")
                }
                throw IOException("Upload failed (HTTP ${resp.code})")
            }
            val bodyStr = resp.body?.string() ?: throw IOException("Empty server response")
            createAdapter.fromJson(bodyStr)?.job_id
                ?: throw IOException("Invalid job_id from server")
        }
        activeRemoteJobId = remoteJobId

        var transientFailures = 0
        var finalDurationMs = 0L
        while (true) {
            coroutineContext.ensureActive()
            val statusReq = Request.Builder()
                .url("$cleanBase/api/jobs/$remoteJobId")
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
                    statusResp.error ?: "Remote Demucs separation failed"
                )
                "cancelled" -> throw kotlinx.coroutines.CancellationException("Remote job cancelled")
                else -> {
                    val mappedPct = (30 + (statusResp.progress * 0.58)).toInt().coerceIn(30, 88)
                    onProgress(
                        SeparationProgress(
                            percent = mappedPct,
                            currentChunk = statusResp.current_chunk,
                            totalChunks = max(1, statusResp.total_chunks),
                            processedMs = (finalDurationMs * statusResp.progress) / 100L,
                            totalDurationMs = finalDurationMs,
                            stage = SeparationStage.SEPARATING
                        )
                    )
                    delay(1200L)
                }
            }
        }

        val stemsDir = File(context.filesDir, "stems/$localJobId").apply { mkdirs() }
        val vocalsWav = File(stemsDir, "vocals.wav")
        val instWav = File(stemsDir, "instrumental.wav")

        onProgress(SeparationProgress(90, 1, 1, finalDurationMs, finalDurationMs, SeparationStage.MERGING))
        downloadFile("$cleanBase/api/jobs/$remoteJobId/download/vocals?format=wav", vocalsWav)
        onProgress(SeparationProgress(95, 1, 1, finalDurationMs, finalDurationMs, SeparationStage.FINALIZING))
        downloadFile("$cleanBase/api/jobs/$remoteJobId/download/instrumental?format=wav", instWav)

        // Optional MP3 download if provided by server
        val vocalsMp3 = File(stemsDir, "vocals.mp3")
        val instMp3 = File(stemsDir, "instrumental.mp3")
        val hasMp3 = runCatching {
            downloadFile("$cleanBase/api/jobs/$remoteJobId/download/vocals?format=mp3", vocalsMp3)
            downloadFile("$cleanBase/api/jobs/$remoteJobId/download/instrumental?format=mp3", instMp3)
            vocalsMp3.length() > 1000L && instMp3.length() > 1000L
        }.getOrDefault(false)
        if (!hasMp3) {
            vocalsMp3.delete()
            instMp3.delete()
        }

        activeRemoteJobId = null

        val wavInfo = inspectWavAndPeaks(vocalsWav)
        val instPeaks = inspectWavAndPeaks(instWav).peaks

        SeparationOutput(
            jobId = localJobId,
            vocalsFile = vocalsWav,
            instrumentalFile = instWav,
            durationMs = max(finalDurationMs, wavInfo.durationMs),
            sampleRate = wavInfo.sampleRate,
            channels = wavInfo.channels,
            vocalsWaveform = wavInfo.peaks,
            instrumentalWaveform = instPeaks
        )
    }

    suspend fun cancelActiveRemoteJob() = withContext(Dispatchers.IO) {
        val jobId = activeRemoteJobId ?: return@withContext
        val base = activeBaseUrl ?: return@withContext
        runCatching {
            val req = Request.Builder()
                .url("$base/api/jobs/$jobId/cancel")
                .post(RequestBody.create(null, ByteArray(0)))
                .build()
            client.newCall(req).execute().close()
        }
        activeRemoteJobId = null
    }

    private fun downloadFile(url: String, target: File) {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download failed: HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty download body")
            body.byteStream().use { input ->
                FileOutputStream(target).use { out ->
                    input.copyTo(out, 65536)
                }
            }
        }
    }

    private data class WavInspection(
        val sampleRate: Int,
        val channels: Int,
        val durationMs: Long,
        val peaks: List<Float>
    )

    private fun inspectWavAndPeaks(file: File): WavInspection {
        if (!file.exists() || file.length() <= WavUtils.HEADER_SIZE) {
            return WavInspection(44100, 2, 0L, List(110) { 0.2f })
        }
        val header = ByteArray(WavUtils.HEADER_SIZE)
        file.inputStream().use { it.read(header) }
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val channels = bb.getShort(22).toInt().coerceIn(1, 2)
        val sampleRate = bb.getInt(24).coerceIn(8000, 96000)
        val dataBytes = (file.length() - WavUtils.HEADER_SIZE).coerceAtLeast(0L)
        val bytesPerSec = sampleRate.toLong() * channels * 2L
        val durationMs = if (bytesPerSec > 0) (dataBytes * 1000L) / bytesPerSec else 0L

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
        return WavInspection(sampleRate, channels, durationMs, WavUtils.downsamplePeaks(rawPeaks))
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
