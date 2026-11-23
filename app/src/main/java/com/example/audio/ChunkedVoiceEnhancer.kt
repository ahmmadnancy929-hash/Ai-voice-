package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

enum class EnhancementStrength(val level: Int, val normalized: Float) {
    LIGHT(0, 0.25f),
    MEDIUM(1, 0.50f),
    STRONG(2, 0.78f),
    FULL_STUDIO(3, 1.0f)
}

enum class VoiceEnhanceStage {
    PREPARING,
    EXTRACTING_AUDIO,
    DENOISING_AND_DEREVERB,
    STUDIO_MASTERING,
    FINALIZING
}

data class VoiceEnhanceProgress(
    val percent: Int,
    val currentChunk: Int,
    val totalChunks: Int,
    val processedMs: Long,
    val totalDurationMs: Long,
    val stage: VoiceEnhanceStage
)

data class VoiceEnhanceOutput(
    val jobId: String,
    val originalWavFile: File,
    val enhancedWavFile: File,
    val durationMs: Long,
    val sampleRate: Int,
    val channels: Int,
    val originalWaveform: List<Float>,
    val enhancedWaveform: List<Float>
)

/**
 * Streaming, chunk-based AI-style Studio Voice Enhancement DSP engine.
 *
 * Supports MP3, WAV, M4A, AAC, FLAC, OGG and audio extracted from video containers
 * (MP4, MKV, MOV, WEBM) with zero artificial duration limits (10m, 30m, 1hr, 2hr+).
 *
 * Processing pipeline per streaming chunk with continuous STFT overlap-add state:
 * 1. 2nd-order Butterworth 80 Hz High-Pass Filter (wind, traffic rumble, handling noise)
 * 2. Adaptive Minimum-Statistics Spectral Noise Estimation + Soft Wiener Denoising
 *    (fan, static hiss, broadband room noise without metallic artifacts)
 * 3. Spectral Late-Reverb Suppression (echo & room reverb tightening)
 * 4. Studio Presence, De-Muffling & Warmth EQ + Soft De-Esser
 * 5. Broadcast Auto-Leveling Envelope Compressor & Soft Limiter
 */
class ChunkedVoiceEnhancer(private val context: Context) {

    companion object {
        private const val FFT_SIZE = 2048
        private const val HOP_SIZE = 512 // 75% overlap for seamless WOLA
        private const val CHUNK_SECONDS = 4
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val WOLA_NORM = 0.5f
    }

    private val fft = FftProcessor(FFT_SIZE)

    suspend fun enhance(
        jobId: String,
        sourceUri: Uri,
        sourceFileSizeBytes: Long,
        strength: EnhancementStrength,
        onProgress: (VoiceEnhanceProgress) -> Unit
    ): VoiceEnhanceOutput = withContext(Dispatchers.IO) {
        onProgress(VoiceEnhanceProgress(2, 0, 1, 0L, 0L, VoiceEnhanceStage.PREPARING))
        checkStorage(sourceFileSizeBytes)

        val outDir = File(context.filesDir, "voice_enhancer/$jobId").apply { mkdirs() }
        val originalWav = File(outDir, "original.wav")
        val enhancedWav = File(outDir, "enhanced.wav")

        try {
            val extractor = MediaExtractor()
            var useRawWavFallback = false
            var sampleRate = 44100
            var sourceChannels = 1
            var durationUs = 0L
            var audioTrackIndex = -1
            var trackFormat: MediaFormat? = null

            try {
                extractor.setDataSource(context, sourceUri, null)
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audioTrackIndex = i
                        trackFormat = fmt
                        break
                    }
                }
                if (audioTrackIndex >= 0 && trackFormat != null) {
                    sampleRate = trackFormat.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44100)
                    sourceChannels = trackFormat.getIntOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
                    durationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                        trackFormat.getLong(MediaFormat.KEY_DURATION)
                    } else 0L
                } else {
                    useRawWavFallback = true
                }
            } catch (_: Exception) {
                useRawWavFallback = true
            }

            if (useRawWavFallback) {
                extractor.release()
                return@withContext processRawWavFallback(
                    jobId = jobId,
                    sourceUri = sourceUri,
                    originalWav = originalWav,
                    enhancedWav = enhancedWav,
                    strength = strength,
                    onProgress = onProgress
                )
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = trackFormat!!.getString(MediaFormat.KEY_MIME)!!
            val outChannels = sourceChannels.coerceIn(1, 2)
            val totalDurationMs = (durationUs / 1000L).coerceAtLeast(1L)
            val estTotalChunks = max(1, ((totalDurationMs / 1000.0) / CHUNK_SECONDS).toInt() + 1)

            val pipeline = StreamingVoiceDspState(
                sampleRate = sampleRate,
                channels = outChannels,
                strength = strength,
                fft = fft
            )

            BufferedOutputStream(FileOutputStream(originalWav), 65536).use { origOut ->
                BufferedOutputStream(FileOutputStream(enhancedWav), 65536).use { enhOut ->
                    WavUtils.writePlaceholderHeader(origOut, sampleRate, outChannels)
                    WavUtils.writePlaceholderHeader(enhOut, sampleRate, outChannels)

                    val decoder = MediaCodec.createDecoderByType(mime)
                    decoder.configure(trackFormat, null, null, 0)
                    decoder.start()

                    var actualSampleRate = sampleRate
                    var actualChannels = sourceChannels
                    val bufferInfo = MediaCodec.BufferInfo()
                    var inputEos = false
                    var outputEos = false

                    val chunkFrames = actualSampleRate * CHUNK_SECONDS
                    val chunkL = FloatArray(chunkFrames)
                    val chunkR = FloatArray(chunkFrames)
                    var chunkPos = 0
                    var chunkIndex = 0
                    var decodedFramesTotal = 0L

                    onProgress(
                        VoiceEnhanceProgress(
                            percent = 4,
                            currentChunk = 0,
                            totalChunks = estTotalChunks,
                            processedMs = 0L,
                            totalDurationMs = totalDurationMs,
                            stage = VoiceEnhanceStage.EXTRACTING_AUDIO
                        )
                    )

                    try {
                        while (!outputEos) {
                            coroutineContext.ensureActive()

                            if (!inputEos) {
                                val inIdx = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                                if (inIdx >= 0) {
                                    val inBuf = decoder.getInputBuffer(inIdx)!!
                                    val sampleSize = extractor.readSampleData(inBuf, 0)
                                    if (sampleSize < 0) {
                                        decoder.queueInputBuffer(
                                            inIdx, 0, 0, 0L,
                                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                        )
                                        inputEos = true
                                    } else {
                                        decoder.queueInputBuffer(
                                            inIdx, 0, sampleSize, extractor.sampleTime, 0
                                        )
                                        extractor.advance()
                                    }
                                }
                            }

                            val outIdx = decoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                            when {
                                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                    val outFmt = decoder.outputFormat
                                    actualSampleRate = outFmt.getIntOrDefault(
                                        MediaFormat.KEY_SAMPLE_RATE, actualSampleRate
                                    )
                                    actualChannels = outFmt.getIntOrDefault(
                                        MediaFormat.KEY_CHANNEL_COUNT, actualChannels
                                    )
                                    pipeline.updateSampleRate(actualSampleRate)
                                }
                                outIdx >= 0 -> {
                                    val outBuf = decoder.getOutputBuffer(outIdx)
                                    if (outBuf != null && bufferInfo.size > 0) {
                                        outBuf.position(bufferInfo.offset)
                                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                        val shortCount = bufferInfo.size / 2
                                        val frames = shortCount / max(1, actualChannels)
                                        val sBuf = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

                                        for (f in 0 until frames) {
                                            val l: Float
                                            val r: Float
                                            if (actualChannels >= 2) {
                                                l = sBuf.get() / 32768f
                                                r = sBuf.get() / 32768f
                                                for (ex in 2 until actualChannels) sBuf.get()
                                            } else {
                                                val m = sBuf.get() / 32768f
                                                l = m
                                                r = m
                                            }
                                            chunkL[chunkPos] = l
                                            chunkR[chunkPos] = r
                                            chunkPos++
                                            decodedFramesTotal++

                                            if (chunkPos >= chunkL.size) {
                                                chunkIndex++
                                                val processedMs = (decodedFramesTotal * 1000L) / actualSampleRate
                                                val pct = if (durationUs > 0) {
                                                    ((processedMs * 88L) / totalDurationMs).toInt().coerceIn(6, 92)
                                                } else {
                                                    (6 + chunkIndex * 3).coerceAtMost(90)
                                                }
                                                onProgress(
                                                    VoiceEnhanceProgress(
                                                        percent = pct,
                                                        currentChunk = chunkIndex,
                                                        totalChunks = max(chunkIndex, estTotalChunks),
                                                        processedMs = processedMs,
                                                        totalDurationMs = max(totalDurationMs, processedMs),
                                                        stage = VoiceEnhanceStage.DENOISING_AND_DEREVERB
                                                    )
                                                )
                                                pipeline.processChunk(chunkL, chunkR, chunkPos, origOut, enhOut)
                                                chunkPos = 0
                                            }
                                        }
                                    }
                                    decoder.releaseOutputBuffer(outIdx, false)
                                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                        outputEos = true
                                    }
                                }
                            }
                        }

                        onProgress(
                            VoiceEnhanceProgress(
                                percent = 94,
                                currentChunk = chunkIndex + 1,
                                totalChunks = max(chunkIndex + 1, estTotalChunks),
                                processedMs = totalDurationMs,
                                totalDurationMs = totalDurationMs,
                                stage = VoiceEnhanceStage.STUDIO_MASTERING
                            )
                        )
                        if (chunkPos > 0) {
                            pipeline.processChunk(chunkL, chunkR, chunkPos, origOut, enhOut)
                        }
                        pipeline.flushTail(origOut, enhOut)
                    } finally {
                        runCatching { decoder.stop() }
                        runCatching { decoder.release() }
                        runCatching { extractor.release() }
                    }
                }
            }

            onProgress(
                VoiceEnhanceProgress(
                    percent = 98,
                    currentChunk = estTotalChunks,
                    totalChunks = estTotalChunks,
                    processedMs = totalDurationMs,
                    totalDurationMs = totalDurationMs,
                    stage = VoiceEnhanceStage.FINALIZING
                )
            )

            WavUtils.patchWavHeader(originalWav, sampleRate, outChannels)
            WavUtils.patchWavHeader(enhancedWav, sampleRate, outChannels)
            val finalDurMs = computeWavDurationMs(enhancedWav, sampleRate, outChannels)

            VoiceEnhanceOutput(
                jobId = jobId,
                originalWavFile = originalWav,
                enhancedWavFile = enhancedWav,
                durationMs = finalDurMs,
                sampleRate = sampleRate,
                channels = outChannels,
                originalWaveform = WavUtils.downsamplePeaks(pipeline.origPeaks),
                enhancedWaveform = WavUtils.downsamplePeaks(pipeline.enhPeaks)
            )
        } catch (t: Throwable) {
            outDir.deleteRecursively()
            throw t
        }
    }

    private suspend fun processRawWavFallback(
        jobId: String,
        sourceUri: Uri,
        originalWav: File,
        enhancedWav: File,
        strength: EnhancementStrength,
        onProgress: (VoiceEnhanceProgress) -> Unit
    ): VoiceEnhanceOutput {
        val stream: InputStream = context.contentResolver.openInputStream(sourceUri)
            ?: throw IllegalStateException("Unable to open audio file")

        return stream.use { input ->
            val header = ByteArray(WavUtils.HEADER_SIZE)
            if (input.read(header) < WavUtils.HEADER_SIZE || String(header, 0, 4) != "RIFF") {
                throw IllegalArgumentException("Unsupported or unreadable audio format")
            }
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val channels = bb.getShort(22).toInt().coerceIn(1, 2)
            val sampleRate = bb.getInt(24).coerceIn(8000, 96000)
            val bitsPerSample = bb.getShort(34).toInt().coerceAtLeast(16)
            val dataSize = bb.getInt(40).toLong().and(0xFFFFFFFFL)
            val bytesPerFrame = channels * (bitsPerSample / 8)
            val totalFrames = if (bytesPerFrame > 0) dataSize / bytesPerFrame else 0L
            val totalDurationMs = if (totalFrames > 0) (totalFrames * 1000L) / sampleRate else 1000L
            val chunkFrames = sampleRate * CHUNK_SECONDS
            val totalChunks = max(1, (totalFrames / chunkFrames).toInt() + 1)

            val pipeline = StreamingVoiceDspState(sampleRate, channels, strength, fft)
            val leftChunk = FloatArray(chunkFrames)
            val rightChunk = FloatArray(chunkFrames)
            val ioBuf = ByteArray(chunkFrames * bytesPerFrame)

            BufferedOutputStream(FileOutputStream(originalWav), 65536).use { origOut ->
                BufferedOutputStream(FileOutputStream(enhancedWav), 65536).use { enhOut ->
                    WavUtils.writePlaceholderHeader(origOut, sampleRate, channels)
                    WavUtils.writePlaceholderHeader(enhOut, sampleRate, channels)

                    var chunkIdx = 0
                    var framesReadTotal = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        var bytesRead = 0
                        while (bytesRead < ioBuf.size) {
                            val r = input.read(ioBuf, bytesRead, ioBuf.size - bytesRead)
                            if (r <= 0) break
                            bytesRead += r
                        }
                        if (bytesRead <= 0) break

                        val framesInBuf = bytesRead / bytesPerFrame
                        val pBuf = ByteBuffer.wrap(ioBuf, 0, framesInBuf * bytesPerFrame)
                            .order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until framesInBuf) {
                            if (channels == 2) {
                                leftChunk[i] = pBuf.short / 32768f
                                rightChunk[i] = pBuf.short / 32768f
                            } else {
                                val m = pBuf.short / 32768f
                                leftChunk[i] = m
                                rightChunk[i] = m
                            }
                        }
                        chunkIdx++
                        framesReadTotal += framesInBuf
                        val pct = ((chunkIdx * 90) / totalChunks).coerceIn(5, 92)
                        onProgress(
                            VoiceEnhanceProgress(
                                percent = pct,
                                currentChunk = chunkIdx,
                                totalChunks = max(chunkIdx, totalChunks),
                                processedMs = (framesReadTotal * 1000L) / sampleRate,
                                totalDurationMs = totalDurationMs,
                                stage = VoiceEnhanceStage.DENOISING_AND_DEREVERB
                            )
                        )
                        pipeline.processChunk(leftChunk, rightChunk, framesInBuf, origOut, enhOut)
                    }
                    onProgress(
                        VoiceEnhanceProgress(95, totalChunks, totalChunks, totalDurationMs, totalDurationMs, VoiceEnhanceStage.STUDIO_MASTERING)
                    )
                    pipeline.flushTail(origOut, enhOut)
                }
            }

            WavUtils.patchWavHeader(originalWav, sampleRate, channels)
            WavUtils.patchWavHeader(enhancedWav, sampleRate, channels)
            VoiceEnhanceOutput(
                jobId = jobId,
                originalWavFile = originalWav,
                enhancedWavFile = enhancedWav,
                durationMs = totalDurationMs,
                sampleRate = sampleRate,
                channels = channels,
                originalWaveform = WavUtils.downsamplePeaks(pipeline.origPeaks),
                enhancedWaveform = WavUtils.downsamplePeaks(pipeline.enhPeaks)
            )
        }
    }

    private fun checkStorage(sourceSizeBytes: Long) {
        val stat = StatFs(context.filesDir.absolutePath)
        val requiredEstimate = max(30L * 1024L * 1024L, sourceSizeBytes * 6L)
        if (stat.availableBytes < requiredEstimate) {
            throw InsufficientStorageException(
                "Insufficient storage (${stat.availableBytes / (1024 * 1024)} MB available) for dual WAV processing."
            )
        }
    }

    private fun computeWavDurationMs(file: File, sampleRate: Int, channels: Int): Long {
        val dataBytes = (file.length() - WavUtils.HEADER_SIZE).coerceAtLeast(0L)
        val bps = sampleRate.toLong() * channels * 2L
        return if (bps > 0L) (dataBytes * 1000L) / bps else 0L
    }

    private fun MediaFormat.getIntOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default

    /**
     * Stateful continuous STFT Voice Enhancement pipeline carrying HPF, noise-floor,
     * late-reverb, gain, and WOLA overlap buffers cleanly across arbitrary chunk boundaries.
     */
    private class StreamingVoiceDspState(
        private var sampleRate: Int,
        private val channels: Int,
        private val strength: EnhancementStrength,
        private val fft: FftProcessor
    ) {
        val origPeaks = ArrayList<Float>(1024)
        val enhPeaks = ArrayList<Float>(1024)

        // 2nd-order Butterworth 80 Hz HPF states (per channel)
        private var hpfB0 = 0f
        private var hpfB1 = 0f
        private var hpfB2 = 0f
        private var hpfA1 = 0f
        private var hpfA2 = 0f
        private var x1L = 0f; private var x2L = 0f; private var y1L = 0f; private var y2L = 0f
        private var x1R = 0f; private var x2R = 0f; private var y1R = 0f; private var y2R = 0f

        // Input & WOLA buffers
        private val origRingL = FloatArray(FFT_SIZE)
        private val origRingR = FloatArray(FFT_SIZE)
        private val hpfRingL = FloatArray(FFT_SIZE)
        private val hpfRingR = FloatArray(FFT_SIZE)
        private var inFill = 0

        private val enhOlaL = FloatArray(FFT_SIZE)
        private val enhOlaR = FloatArray(FFT_SIZE)
        private val origPassBufL = FloatArray(FFT_SIZE)
        private val origPassBufR = FloatArray(FFT_SIZE)

        // Spectral scratch
        private val specReL = FloatArray(FFT_SIZE)
        private val specImL = FloatArray(FFT_SIZE)
        private val specReR = FloatArray(FFT_SIZE)
        private val specImR = FloatArray(FFT_SIZE)

        // Per-bin adaptive noise floor + late reverb + smoothed gain
        private val halfBins = FFT_SIZE / 2 + 1
        private val noiseFloor = FloatArray(halfBins) { 1e-5f }
        private val lateReverb = FloatArray(halfBins)
        private val prevGain = FloatArray(halfBins) { 1f }
        private val studioEqCurve = FloatArray(halfBins) { 1f }
        private var frameCounter = 0

        // Broadcast auto-leveling envelope
        private var envRms = 0.05f
        private var peakWindowFrames = (sampleRate / 10).coerceAtLeast(512)
        private var peakCounter = 0
        private var curOrigPeak = 0f
        private var curEnhPeak = 0f

        init {
            configureFilters()
        }

        fun updateSampleRate(newRate: Int) {
            if (newRate != sampleRate && newRate > 0) {
                sampleRate = newRate
                peakWindowFrames = (sampleRate / 10).coerceAtLeast(512)
                configureFilters()
            }
        }

        private fun configureFilters() {
            // 2nd-order Butterworth High-Pass at 85 Hz (removes wind/traffic/mic rumble)
            val fc = 85.0
            val w0 = 2.0 * PI * fc / sampleRate
            val cosW0 = cos(w0)
            val alpha = sin(w0) / sqrt(2.0)
            val a0 = 1.0 + alpha
            hpfB0 = (((1.0 + cosW0) / 2.0) / a0).toFloat()
            hpfB1 = ((-(1.0 + cosW0)) / a0).toFloat()
            hpfB2 = (((1.0 + cosW0) / 2.0) / a0).toFloat()
            hpfA1 = ((-2.0 * cosW0) / a0).toFloat()
            hpfA2 = ((1.0 - alpha) / a0).toFloat()

            // Studio vocal presence + warmth + de-muffle + gentle de-esser EQ curve
            val eqScale = 0.35f + strength.normalized * 0.65f
            for (k in 0 until halfBins) {
                val freq = k.toFloat() * sampleRate / FFT_SIZE
                // Warm chest resonance around 190 Hz
                val warmthDb = 1.4f * exp(-((freq - 190f) / 110f) * ((freq - 190f) / 110f))
                // De-muffle & intelligibility presence boost around 2.8 kHz
                val presenceDb = 3.2f * exp(-((freq - 2850f) / 1350f) * ((freq - 2850f) / 1350f))
                // Gentle air/clarity lift around 5.2 kHz, soft harshness/sibilance tame around 7.5 kHz
                val harshCutDb = -2.2f * exp(-((freq - 7500f) / 1400f) * ((freq - 7500f) / 1400f))
                val totalDb = (warmthDb + presenceDb + harshCutDb) * eqScale
                studioEqCurve[k] = Math.pow(10.0, totalDb / 20.0).toFloat()
            }
        }

        fun processChunk(
            inL: FloatArray,
            inR: FloatArray,
            frameCount: Int,
            origOut: OutputStream,
            enhOut: OutputStream
        ) {
            var idx = 0
            while (idx < frameCount) {
                val space = FFT_SIZE - inFill
                val toCopy = min(space, frameCount - idx)

                for (i in 0 until toCopy) {
                    val rawL = inL[idx + i]
                    val rawR = inR[idx + i]
                    origRingL[inFill + i] = rawL
                    origRingR[inFill + i] = rawR

                    val hpL = hpfB0 * rawL + hpfB1 * x1L + hpfB2 * x2L - hpfA1 * y1L - hpfA2 * y2L
                    x2L = x1L; x1L = rawL; y2L = y1L; y1L = hpL
                    hpfRingL[inFill + i] = hpL

                    if (channels == 2) {
                        val hpR = hpfB0 * rawR + hpfB1 * x1R + hpfB2 * x2R - hpfA1 * y1R - hpfA2 * y2R
                        x2R = x1R; x1R = rawR; y2R = y1R; y1R = hpR
                        hpfRingR[inFill + i] = hpR
                    } else {
                        hpfRingR[inFill + i] = hpL
                    }
                }

                inFill += toCopy
                idx += toCopy

                if (inFill == FFT_SIZE) {
                    processStftHop(origOut, enhOut)
                    val keep = FFT_SIZE - HOP_SIZE
                    System.arraycopy(origRingL, HOP_SIZE, origRingL, 0, keep)
                    System.arraycopy(origRingR, HOP_SIZE, origRingR, 0, keep)
                    System.arraycopy(hpfRingL, HOP_SIZE, hpfRingL, 0, keep)
                    System.arraycopy(hpfRingR, HOP_SIZE, hpfRingR, 0, keep)
                    inFill = keep
                }
            }
        }

        fun flushTail(origOut: OutputStream, enhOut: OutputStream) {
            if (inFill > 0) {
                for (i in inFill until FFT_SIZE) {
                    origRingL[i] = 0f; origRingR[i] = 0f
                    hpfRingL[i] = 0f; hpfRingR[i] = 0f
                }
                processStftHop(origOut, enhOut)
            }
            if (curOrigPeak > 0f || curEnhPeak > 0f) {
                origPeaks.add(curOrigPeak)
                enhPeaks.add(curEnhPeak)
            }
        }

        private fun processStftHop(origOut: OutputStream, enhOut: OutputStream) {
            frameCounter++
            val win = fft.sqrtHannWindow
            for (i in 0 until FFT_SIZE) {
                val w = win[i]
                specReL[i] = hpfRingL[i] * w
                specImL[i] = 0f
                origPassBufL[i] = origRingL[i]
                if (channels == 2) {
                    specReR[i] = hpfRingR[i] * w
                    specImR[i] = 0f
                    origPassBufR[i] = origRingR[i]
                }
            }

            fft.fft(specReL, specImL)
            if (channels == 2) fft.fft(specReR, specImR)

            val s = strength.normalized
            val noiseOverSub = 0.9f + s * 1.45f
            val gainFloor = (0.28f - s * 0.22f).coerceIn(0.05f, 0.30f)
            val dereverbStrength = s * 0.42f

            for (k in 0 until halfBins) {
                val pL = specReL[k] * specReL[k] + specImL[k] * specImL[k]
                val power = if (channels == 2) {
                    0.5f * (pL + specReR[k] * specReR[k] + specImR[k] * specImR[k])
                } else {
                    pL
                }

                // Adaptive noise floor tracking: fast decay during silence, slow rise during speech
                if (frameCounter <= 8) {
                    noiseFloor[k] = 0.7f * noiseFloor[k] + 0.3f * power
                } else if (power < noiseFloor[k]) {
                    noiseFloor[k] = 0.88f * noiseFloor[k] + 0.12f * power
                } else {
                    noiseFloor[k] = 0.9965f * noiseFloor[k] + 0.0035f * power
                }

                // Late reverb spectral decay estimate
                lateReverb[k] = 0.72f * lateReverb[k] + 0.28f * power
                val effectiveNoise = noiseFloor[k] * noiseOverSub + lateReverb[k] * dereverbStrength * 0.35f
                val snr = ((power - effectiveNoise) / (effectiveNoise + 1e-12f)).coerceAtLeast(0f)

                // Soft Wiener gain + temporal smoothing eliminates musical noise
                val targetGain = (snr / (snr + 0.85f)).coerceIn(gainFloor, 1.0f)
                val smoothedGain = if (targetGain > prevGain[k]) {
                    0.55f * prevGain[k] + 0.45f * targetGain
                } else {
                    0.74f * prevGain[k] + 0.26f * targetGain
                }
                prevGain[k] = smoothedGain

                val finalBinGain = smoothedGain * studioEqCurve[k]

                specReL[k] *= finalBinGain
                specImL[k] *= finalBinGain
                if (channels == 2) {
                    specReR[k] *= finalBinGain
                    specImR[k] *= finalBinGain
                }

                if (k in 1 until (FFT_SIZE / 2)) {
                    val m = FFT_SIZE - k
                    specReL[m] = specReL[k]
                    specImL[m] = -specImL[k]
                    if (channels == 2) {
                        specReR[m] = specReR[k]
                        specImR[m] = -specImR[k]
                    }
                }
            }

            fft.ifft(specReL, specImL)
            if (channels == 2) fft.ifft(specReR, specImR)

            for (i in 0 until FFT_SIZE) {
                val w = win[i] * WOLA_NORM
                enhOlaL[i] += specReL[i] * w
                if (channels == 2) enhOlaR[i] += specReR[i] * w
            }

            // Broadcast auto-leveling & emit HOP_SIZE frames
            val bytesNeeded = HOP_SIZE * channels * 2
            val origBytes = ByteArray(bytesNeeded)
            val enhBytes = ByteArray(bytesNeeded)
            val oBb = ByteBuffer.wrap(origBytes).order(ByteOrder.LITTLE_ENDIAN)
            val eBb = ByteBuffer.wrap(enhBytes).order(ByteOrder.LITTLE_ENDIAN)

            val targetLevel = 0.22f + s * 0.10f
            val maxMakeup = 1.6f + s * 1.4f

            for (i in 0 until HOP_SIZE) {
                val oL = origPassBufL[i].coerceIn(-1f, 1f)
                val oR = if (channels == 2) origPassBufR[i].coerceIn(-1f, 1f) else oL

                val rawEL = enhOlaL[i]
                val rawER = if (channels == 2) enhOlaR[i] else rawEL
                val instAmp = max(abs(rawEL), abs(rawER))

                // Smooth RMS envelope follower
                envRms = if (instAmp > envRms) {
                    0.96f * envRms + 0.04f * instAmp
                } else {
                    0.9992f * envRms + 0.0008f * instAmp
                }
                val autoGain = if (envRms > 0.015f) {
                    (targetLevel / envRms).coerceIn(0.65f, maxMakeup)
                } else {
                    1.0f
                }

                // Soft tanh studio limiter prevents digital clipping
                val eL = tanh((rawEL * autoGain).toDouble()).toFloat().coerceIn(-0.98f, 0.98f)
                val eR = tanh((rawER * autoGain).toDouble()).toFloat().coerceIn(-0.98f, 0.98f)

                if (channels == 2) {
                    oBb.putShort((oL * 32767f).toInt().toShort())
                    oBb.putShort((oR * 32767f).toInt().toShort())
                    eBb.putShort((eL * 32767f).toInt().toShort())
                    eBb.putShort((eR * 32767f).toInt().toShort())
                } else {
                    oBb.putShort((oL * 32767f).toInt().toShort())
                    eBb.putShort((eL * 32767f).toInt().toShort())
                }

                val oAmp = max(abs(oL), abs(oR))
                val eAmp = max(abs(eL), abs(eR))
                if (oAmp > curOrigPeak) curOrigPeak = oAmp
                if (eAmp > curEnhPeak) curEnhPeak = eAmp
                peakCounter++
                if (peakCounter >= peakWindowFrames) {
                    origPeaks.add(curOrigPeak)
                    enhPeaks.add(curEnhPeak)
                    curOrigPeak = 0f
                    curEnhPeak = 0f
                    peakCounter = 0
                }
            }

            origOut.write(origBytes)
            enhOut.write(enhBytes)

            val remaining = FFT_SIZE - HOP_SIZE
            System.arraycopy(enhOlaL, HOP_SIZE, enhOlaL, 0, remaining)
            System.arraycopy(enhOlaR, HOP_SIZE, enhOlaR, 0, remaining)
            for (i in remaining until FFT_SIZE) {
                enhOlaL[i] = 0f
                enhOlaR[i] = 0f
            }
        }
    }
}
