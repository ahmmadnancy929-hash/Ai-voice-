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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

enum class SeparationStage {
    PREPARING,
    DECODING,
    SEPARATING,
    MERGING,
    FINALIZING
}

data class SeparationProgress(
    val percent: Int,
    val currentChunk: Int,
    val totalChunks: Int,
    val processedMs: Long,
    val totalDurationMs: Long,
    val stage: SeparationStage
)

data class SeparationOutput(
    val jobId: String,
    val vocalsFile: File,
    val instrumentalFile: File,
    val durationMs: Long,
    val sampleRate: Int,
    val channels: Int,
    val vocalsWaveform: List<Float>,
    val instrumentalWaveform: List<Float>
)

class InsufficientStorageException(message: String) : Exception(message)

/**
 * Streaming, chunk-based Overlap-Add STFT Vocal & Instrumental Separator.
 *
 * Uses MediaExtractor + MediaCodec (or direct WAV stream fallback) to decode audio
 * incrementally in bounded chunks (~4s per chunk) so files of any duration
 * (10 min, 30 min, 1 hr+) run in constant low memory without arbitrary caps.
 */
class ChunkedVocalSeparator(private val context: Context) {

    companion object {
        private const val FFT_SIZE = 2048
        private const val HOP_SIZE = 512 // 75% overlap for click-free WOLA reconstruction
        private const val CHUNK_SECONDS = 4
        private const val CODEC_TIMEOUT_US = 10_000L
        // 2x overlap factor for 75% WOLA synthesis with sqrt-Hann * sqrt-Hann = Hann (sum = 2.0)
        private const val WOLA_NORM = 0.5f
    }

    private val fftProcessor = FftProcessor(FFT_SIZE)

    suspend fun separate(
        jobId: String,
        sourceUri: Uri,
        sourceFileSizeBytes: Long,
        isolationStrength: Float,
        onProgress: (SeparationProgress) -> Unit
    ): SeparationOutput = withContext(Dispatchers.IO) {
        onProgress(SeparationProgress(2, 0, 1, 0L, 0L, SeparationStage.PREPARING))
        checkAvailableStorage(sourceFileSizeBytes)

        val stemsDir = File(context.filesDir, "stems/$jobId").apply { mkdirs() }
        val tempDir = File(context.cacheDir, "vocal_temp/$jobId").apply { mkdirs() }
        val vocalsFile = File(stemsDir, "vocals.wav")
        val instrumentalFile = File(stemsDir, "instrumental.wav")

        try {
            val extractor = MediaExtractor()
            var usingRawWavFallback = false
            var sampleRate = 44100
            var sourceChannels = 2
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
                    sampleRate = trackFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44100)
                    sourceChannels = trackFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
                    durationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                        trackFormat.getLong(MediaFormat.KEY_DURATION)
                    } else 0L
                } else {
                    usingRawWavFallback = true
                }
            } catch (_: Exception) {
                usingRawWavFallback = true
            }

            if (usingRawWavFallback) {
                extractor.release()
                return@withContext processRawWavStream(
                    jobId = jobId,
                    sourceUri = sourceUri,
                    vocalsFile = vocalsFile,
                    instrumentalFile = instrumentalFile,
                    isolationStrength = isolationStrength,
                    onProgress = onProgress
                )
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = trackFormat!!.getString(MediaFormat.KEY_MIME)!!
            val outChannels = if (sourceChannels >= 2) 2 else 1
            val totalDurationMs = (durationUs / 1000L).coerceAtLeast(1L)
            val estTotalChunks = max(1, ((totalDurationMs / 1000.0) / CHUNK_SECONDS).toInt() + 1)

            val state = StreamingStftState(
                sampleRate = sampleRate,
                channels = outChannels,
                isolationStrength = isolationStrength,
                fft = fftProcessor
            )

            BufferedOutputStream(FileOutputStream(vocalsFile), 65536).use { vocOut ->
                BufferedOutputStream(FileOutputStream(instrumentalFile), 65536).use { instOut ->
                    WavUtils.writePlaceholderHeader(vocOut, sampleRate, outChannels)
                    WavUtils.writePlaceholderHeader(instOut, sampleRate, outChannels)

                    val decoder = MediaCodec.createDecoderByType(mime)
                    decoder.configure(trackFormat, null, null, 0)
                    decoder.start()

                    var actualSampleRate = sampleRate
                    var actualChannels = sourceChannels
                    val bufferInfo = MediaCodec.BufferInfo()
                    var inputEos = false
                    var outputEos = false

                    val chunkTargetFrames = actualSampleRate * CHUNK_SECONDS
                    var chunkLeft = FloatArray(chunkTargetFrames)
                    var chunkRight = FloatArray(chunkTargetFrames)
                    var chunkFramePos = 0
                    var currentChunkIndex = 0
                    var decodedSamplesTotal = 0L

                    // Reusable byte buffer for chunk PCM write
                    val writeByteBuf = ByteBuffer.allocate(chunkTargetFrames * outChannels * 2)
                        .order(ByteOrder.LITTLE_ENDIAN)

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
                                    actualSampleRate = outFmt.getIntegerOrDefault(
                                        MediaFormat.KEY_SAMPLE_RATE, actualSampleRate
                                    )
                                    actualChannels = outFmt.getIntegerOrDefault(
                                        MediaFormat.KEY_CHANNEL_COUNT, actualChannels
                                    )
                                    state.updateSampleRate(actualSampleRate)
                                }
                                outIdx >= 0 -> {
                                    val outBuf = decoder.getOutputBuffer(outIdx)
                                    if (outBuf != null && bufferInfo.size > 0) {
                                        outBuf.position(bufferInfo.offset)
                                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                        val pcmShortCount = bufferInfo.size / 2
                                        val frames = pcmShortCount / max(1, actualChannels)
                                        val shortBuf = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

                                        for (f in 0 until frames) {
                                            val lSample: Float
                                            val rSample: Float
                                            if (actualChannels >= 2) {
                                                lSample = shortBuf.get() / 32768f
                                                rSample = shortBuf.get() / 32768f
                                                for (extra in 2 until actualChannels) {
                                                    shortBuf.get()
                                                }
                                            } else {
                                                val m = shortBuf.get() / 32768f
                                                lSample = m
                                                rSample = m
                                            }

                                            chunkLeft[chunkFramePos] = lSample
                                            chunkRight[chunkFramePos] = rSample
                                            chunkFramePos++
                                            decodedSamplesTotal++

                                            if (chunkFramePos >= chunkLeft.size) {
                                                currentChunkIndex++
                                                val totalChunksDisplay = max(currentChunkIndex, estTotalChunks)
                                                val processedMs = (decodedSamplesTotal * 1000L) / actualSampleRate
                                                val pct = if (durationUs > 0) {
                                                    ((processedMs * 90L) / totalDurationMs).toInt().coerceIn(5, 92)
                                                } else {
                                                    (5 + (currentChunkIndex * 3)).coerceAtMost(90)
                                                }
                                                onProgress(
                                                    SeparationProgress(
                                                        percent = pct,
                                                        currentChunk = currentChunkIndex,
                                                        totalChunks = totalChunksDisplay,
                                                        processedMs = processedMs,
                                                        totalDurationMs = max(totalDurationMs, processedMs),
                                                        stage = SeparationStage.SEPARATING
                                                    )
                                                )

                                                state.processAndWriteChunk(
                                                    leftIn = chunkLeft,
                                                    rightIn = chunkRight,
                                                    frameCount = chunkFramePos,
                                                    vocOut = vocOut,
                                                    instOut = instOut,
                                                    byteBuf = writeByteBuf
                                                )
                                                chunkFramePos = 0
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

                        // Flush remaining tail chunk + STFT overlap buffer
                        onProgress(
                            SeparationProgress(
                                percent = 94,
                                currentChunk = currentChunkIndex + 1,
                                totalChunks = max(currentChunkIndex + 1, estTotalChunks),
                                processedMs = (decodedSamplesTotal * 1000L) / actualSampleRate,
                                totalDurationMs = max(totalDurationMs, (decodedSamplesTotal * 1000L) / actualSampleRate),
                                stage = SeparationStage.MERGING
                            )
                        )
                        if (chunkFramePos > 0) {
                            state.processAndWriteChunk(
                                leftIn = chunkLeft,
                                rightIn = chunkRight,
                                frameCount = chunkFramePos,
                                vocOut = vocOut,
                                instOut = instOut,
                                byteBuf = writeByteBuf
                            )
                        }
                        state.flushTail(vocOut, instOut, writeByteBuf)
                    } finally {
                        runCatching { decoder.stop() }
                        runCatching { decoder.release() }
                        runCatching { extractor.release() }
                    }
                }
            }

            onProgress(
                SeparationProgress(
                    percent = 98,
                    currentChunk = estTotalChunks,
                    totalChunks = estTotalChunks,
                    processedMs = totalDurationMs,
                    totalDurationMs = totalDurationMs,
                    stage = SeparationStage.FINALIZING
                )
            )

            WavUtils.patchWavHeader(vocalsFile, sampleRate, outChannels)
            WavUtils.patchWavHeader(instrumentalFile, sampleRate, outChannels)
            tempDir.deleteRecursively()

            val finalDurationMs = computeWavDurationMs(vocalsFile, sampleRate, outChannels)

            SeparationOutput(
                jobId = jobId,
                vocalsFile = vocalsFile,
                instrumentalFile = instrumentalFile,
                durationMs = finalDurationMs,
                sampleRate = sampleRate,
                channels = outChannels,
                vocalsWaveform = WavUtils.downsamplePeaks(state.vocalsPeaks),
                instrumentalWaveform = WavUtils.downsamplePeaks(state.instrumentalPeaks)
            )
        } catch (t: Throwable) {
            stemsDir.deleteRecursively()
            tempDir.deleteRecursively()
            throw t
        }
    }

    private suspend fun processRawWavStream(
        jobId: String,
        sourceUri: Uri,
        vocalsFile: File,
        instrumentalFile: File,
        isolationStrength: Float,
        onProgress: (SeparationProgress) -> Unit
    ): SeparationOutput {
        val inputStream: InputStream = context.contentResolver.openInputStream(sourceUri)
            ?: throw IllegalStateException("Unable to open audio stream")

        return inputStream.use { stream ->
            val header = ByteArray(44)
            val readHeader = stream.read(header)
            if (readHeader < 44 || String(header, 0, 4) != "RIFF") {
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

            val state = StreamingStftState(sampleRate, channels, isolationStrength, fftProcessor)
            val leftChunk = FloatArray(chunkFrames)
            val rightChunk = FloatArray(chunkFrames)
            val ioBuf = ByteArray(chunkFrames * bytesPerFrame)
            val writeByteBuf = ByteBuffer.allocate(chunkFrames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)

            BufferedOutputStream(FileOutputStream(vocalsFile), 65536).use { vocOut ->
                BufferedOutputStream(FileOutputStream(instrumentalFile), 65536).use { instOut ->
                    WavUtils.writePlaceholderHeader(vocOut, sampleRate, channels)
                    WavUtils.writePlaceholderHeader(instOut, sampleRate, channels)

                    var chunkIndex = 0
                    var totalReadFrames = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        var bytesRead = 0
                        while (bytesRead < ioBuf.size) {
                            val r = stream.read(ioBuf, bytesRead, ioBuf.size - bytesRead)
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
                        chunkIndex++
                        totalReadFrames += framesInBuf
                        val pct = ((chunkIndex * 90) / totalChunks).coerceIn(5, 92)
                        onProgress(
                            SeparationProgress(
                                percent = pct,
                                currentChunk = chunkIndex,
                                totalChunks = max(chunkIndex, totalChunks),
                                processedMs = (totalReadFrames * 1000L) / sampleRate,
                                totalDurationMs = totalDurationMs,
                                stage = SeparationStage.SEPARATING
                            )
                        )
                        state.processAndWriteChunk(leftChunk, rightChunk, framesInBuf, vocOut, instOut, writeByteBuf)
                    }

                    onProgress(
                        SeparationProgress(95, totalChunks, totalChunks, totalDurationMs, totalDurationMs, SeparationStage.MERGING)
                    )
                    state.flushTail(vocOut, instOut, writeByteBuf)
                }
            }

            WavUtils.patchWavHeader(vocalsFile, sampleRate, channels)
            WavUtils.patchWavHeader(instrumentalFile, sampleRate, channels)
            SeparationOutput(
                jobId = jobId,
                vocalsFile = vocalsFile,
                instrumentalFile = instrumentalFile,
                durationMs = totalDurationMs,
                sampleRate = sampleRate,
                channels = channels,
                vocalsWaveform = WavUtils.downsamplePeaks(state.vocalsPeaks),
                instrumentalWaveform = WavUtils.downsamplePeaks(state.instrumentalPeaks)
            )
        }
    }

    private fun checkAvailableStorage(sourceSizeBytes: Long) {
        val stat = StatFs(context.filesDir.absolutePath)
        val available = stat.availableBytes
        // Estimate decoded PCM size roughly (10x compressed max or minimum 25MB headroom)
        val requiredEstimate = max(25L * 1024L * 1024L, sourceSizeBytes * 6L)
        if (available < requiredEstimate) {
            throw InsufficientStorageException(
                "Available storage (${available / (1024 * 1024)} MB) is insufficient for lossless stem separation."
            )
        }
    }

    private fun computeWavDurationMs(wavFile: File, sampleRate: Int, channels: Int): Long {
        val dataBytes = (wavFile.length() - WavUtils.HEADER_SIZE).coerceAtLeast(0L)
        val bytesPerSecond = sampleRate.toLong() * channels * 2L
        return if (bytesPerSecond > 0) (dataBytes * 1000L) / bytesPerSecond else 0L
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default

    /**
     * Maintains continuous STFT overlap-add buffers across arbitrary chunk boundaries
     * so long tracks (1hr+) have zero discontinuities or clicking at chunk edges.
     */
    private class StreamingStftState(
        private var sampleRate: Int,
        private val channels: Int,
        private val isolationStrength: Float,
        private val fft: FftProcessor
    ) {
        val vocalsPeaks = ArrayList<Float>(1024)
        val instrumentalPeaks = ArrayList<Float>(1024)

        private val inRingL = FloatArray(FFT_SIZE)
        private val inRingR = FloatArray(FFT_SIZE)
        private var inFill = 0

        // Overlap-Add output accumulators (FFT_SIZE length)
        private val vocOlaL = FloatArray(FFT_SIZE)
        private val vocOlaR = FloatArray(FFT_SIZE)
        private val instOlaL = FloatArray(FFT_SIZE)
        private val instOlaR = FloatArray(FFT_SIZE)

        // FFT scratch buffers
        private val midRe = FloatArray(FFT_SIZE)
        private val midIm = FloatArray(FFT_SIZE)
        private val sideRe = FloatArray(FFT_SIZE)
        private val sideIm = FloatArray(FFT_SIZE)
        private val vocMidRe = FloatArray(FFT_SIZE)
        private val vocMidIm = FloatArray(FFT_SIZE)
        private val instMidRe = FloatArray(FFT_SIZE)
        private val instMidIm = FloatArray(FFT_SIZE)
        private val vocSideRe = FloatArray(FFT_SIZE)
        private val vocSideIm = FloatArray(FFT_SIZE)

        private val vocalBandWeights = FloatArray(FFT_SIZE / 2 + 1)

        // Peak accumulator every ~4410 frames (~100ms)
        private var peakSampleCounter = 0
        private var currentVocPeak = 0f
        private var currentInstPeak = 0f
        private var peakWindowFrames = (sampleRate / 10).coerceAtLeast(512)

        init {
            rebuildVocalBandWeights()
        }

        fun updateSampleRate(newRate: Int) {
            if (newRate != sampleRate && newRate > 0) {
                sampleRate = newRate
                peakWindowFrames = (sampleRate / 10).coerceAtLeast(512)
                rebuildVocalBandWeights()
            }
        }

        private fun rebuildVocalBandWeights() {
            val half = FFT_SIZE / 2
            for (k in 0..half) {
                val freq = k.toFloat() * sampleRate / FFT_SIZE
                // Smooth bandpass weight: sub-bass (< 85 Hz) kept in instrumental,
                // peak vocal formants (220 Hz - 4.8 kHz), gentle rolloff above 6.5 kHz for hi-hats
                val lowCut = when {
                    freq < 75f -> 0.02f
                    freq < 220f -> {
                        val t = (freq - 75f) / (220f - 75f)
                        0.02f + 0.98f * (0.5f - 0.5f * cos(PI * t).toFloat())
                    }
                    else -> 1.0f
                }
                val highCut = when {
                    freq <= 5000f -> 1.0f
                    freq < 9500f -> {
                        val t = (freq - 5000f) / (9500f - 5000f)
                        0.15f + 0.85f * (0.5f + 0.5f * cos(PI * t).toFloat())
                    }
                    else -> 0.15f
                }
                vocalBandWeights[k] = (lowCut * highCut).coerceIn(0.02f, 1.0f)
            }
        }

        fun processAndWriteChunk(
            leftIn: FloatArray,
            rightIn: FloatArray,
            frameCount: Int,
            vocOut: OutputStream,
            instOut: OutputStream,
            byteBuf: ByteBuffer
        ) {
            byteBuf.clear()
            var idx = 0
            while (idx < frameCount) {
                val needed = FFT_SIZE - inFill
                val toCopy = min(needed, frameCount - idx)
                System.arraycopy(leftIn, idx, inRingL, inFill, toCopy)
                System.arraycopy(rightIn, idx, inRingR, inFill, toCopy)
                inFill += toCopy
                idx += toCopy

                if (inFill == FFT_SIZE) {
                    processStftFrameAndEmitHop(vocOut, instOut, byteBuf)
                    // Shift input ring left by HOP_SIZE
                    System.arraycopy(inRingL, HOP_SIZE, inRingL, 0, FFT_SIZE - HOP_SIZE)
                    System.arraycopy(inRingR, HOP_SIZE, inRingR, 0, FFT_SIZE - HOP_SIZE)
                    inFill = FFT_SIZE - HOP_SIZE
                }
            }
            flushByteBuffer(byteBuf, vocOut, instOut)
        }

        fun flushTail(vocOut: OutputStream, instOut: OutputStream, byteBuf: ByteBuffer) {
            byteBuf.clear()
            if (inFill > 0) {
                for (i in inFill until FFT_SIZE) {
                    inRingL[i] = 0f
                    inRingR[i] = 0f
                }
                processStftFrameAndEmitHop(vocOut, instOut, byteBuf)
            }
            if (currentVocPeak > 0f || currentInstPeak > 0f) {
                vocalsPeaks.add(currentVocPeak)
                instrumentalPeaks.add(currentInstPeak)
            }
        }

        private fun processStftFrameAndEmitHop(
            vocOut: OutputStream,
            instOut: OutputStream,
            byteBuf: ByteBuffer
        ) {
            val win = fft.sqrtHannWindow
            for (i in 0 until FFT_SIZE) {
                val l = inRingL[i]
                val r = inRingR[i]
                val w = win[i]
                midRe[i] = 0.5f * (l + r) * w
                midIm[i] = 0f
                sideRe[i] = 0.5f * (l - r) * w
                sideIm[i] = 0f
            }

            fft.fft(midRe, midIm)
            if (channels == 2) {
                fft.fft(sideRe, sideIm)
            }

            val half = FFT_SIZE / 2
            val panSharpness = 1.4f + isolationStrength * 2.2f
            val maskExponent = 1.15f + (1f - isolationStrength) * 0.6f
            val instAttenuation = 0.85f + isolationStrength * 0.15f

            for (k in 0..half) {
                val mRe = midRe[k]
                val mIm = midIm[k]
                val sRe = sideRe[k]
                val sIm = sideIm[k]

                val magMid2 = mRe * mRe + mIm * mIm
                val magSide2 = sRe * sRe + sIm * sIm

                val vocalMask = if (channels == 2) {
                    val centerRatio = magMid2 / (magMid2 + magSide2 * panSharpness + 1e-12f)
                    val shaped = centerRatio.pow(maskExponent) * vocalBandWeights[k]
                    shaped.coerceIn(0f, 1f)
                } else {
                    // Mono fallback: isolate harmonic vocal presence band smoothly
                    (vocalBandWeights[k] * (0.45f + 0.45f * isolationStrength)).coerceIn(0f, 0.92f)
                }

                val instMask = (1f - vocalMask * instAttenuation).coerceIn(0f, 1f)
                val sideBleed = 0.10f * vocalMask

                vocMidRe[k] = mRe * vocalMask
                vocMidIm[k] = mIm * vocalMask
                instMidRe[k] = mRe * instMask
                instMidIm[k] = mIm * instMask
                vocSideRe[k] = sRe * sideBleed
                vocSideIm[k] = sIm * sideBleed

                if (k in 1 until half) {
                    val mirror = FFT_SIZE - k
                    vocMidRe[mirror] = vocMidRe[k]
                    vocMidIm[mirror] = -vocMidIm[k]
                    instMidRe[mirror] = instMidRe[k]
                    instMidIm[mirror] = -instMidIm[k]
                    vocSideRe[mirror] = vocSideRe[k]
                    vocSideIm[mirror] = -vocSideIm[k]
                }
            }

            fft.ifft(vocMidRe, vocMidIm)
            fft.ifft(instMidRe, instMidIm)
            if (channels == 2) {
                fft.ifft(vocSideRe, vocSideIm)
                fft.ifft(sideRe, sideIm)
            }

            for (i in 0 until FFT_SIZE) {
                val w = win[i] * WOLA_NORM
                val vM = vocMidRe[i] * w
                val vS = if (channels == 2) vocSideRe[i] * w else 0f
                vocOlaL[i] += vM + vS
                vocOlaR[i] += vM - vS

                val iM = instMidRe[i] * w
                val iS = if (channels == 2) sideRe[i] * w else 0f
                instOlaL[i] += iM + iS
                instOlaR[i] += iM - iS
            }

            // Emit first HOP_SIZE samples
            val neededBytes = HOP_SIZE * channels * 2
            if (byteBuf.remaining() < neededBytes) {
                flushByteBuffer(byteBuf, vocOut, instOut)
            }

            // Write Vocals hop & track waveform peaks
            val vocBytes = ByteArray(neededBytes)
            val instBytes = ByteArray(neededBytes)
            val vBb = ByteBuffer.wrap(vocBytes).order(ByteOrder.LITTLE_ENDIAN)
            val iBb = ByteBuffer.wrap(instBytes).order(ByteOrder.LITTLE_ENDIAN)

            for (i in 0 until HOP_SIZE) {
                val vl = vocOlaL[i].coerceIn(-1f, 1f)
                val vr = vocOlaR[i].coerceIn(-1f, 1f)
                val il = instOlaL[i].coerceIn(-1f, 1f)
                val ir = instOlaR[i].coerceIn(-1f, 1f)

                if (channels == 2) {
                    vBb.putShort((vl * 32767f).toInt().toShort())
                    vBb.putShort((vr * 32767f).toInt().toShort())
                    iBb.putShort((il * 32767f).toInt().toShort())
                    iBb.putShort((ir * 32767f).toInt().toShort())
                } else {
                    vBb.putShort((vl * 32767f).toInt().toShort())
                    iBb.putShort((il * 32767f).toInt().toShort())
                }

                val vAmp = max(abs(vl), abs(vr))
                val iAmp = max(abs(il), abs(ir))
                if (vAmp > currentVocPeak) currentVocPeak = vAmp
                if (iAmp > currentInstPeak) currentInstPeak = iAmp
                peakSampleCounter++
                if (peakSampleCounter >= peakWindowFrames) {
                    vocalsPeaks.add(currentVocPeak)
                    instrumentalPeaks.add(currentInstPeak)
                    currentVocPeak = 0f
                    currentInstPeak = 0f
                    peakSampleCounter = 0
                }
            }

            vocOut.write(vocBytes)
            instOut.write(instBytes)

            // Shift OLA accumulators left by HOP_SIZE and zero tail
            val remaining = FFT_SIZE - HOP_SIZE
            System.arraycopy(vocOlaL, HOP_SIZE, vocOlaL, 0, remaining)
            System.arraycopy(vocOlaR, HOP_SIZE, vocOlaR, 0, remaining)
            System.arraycopy(instOlaL, HOP_SIZE, instOlaL, 0, remaining)
            System.arraycopy(instOlaR, HOP_SIZE, instOlaR, 0, remaining)
            for (i in remaining until FFT_SIZE) {
                vocOlaL[i] = 0f
                vocOlaR[i] = 0f
                instOlaL[i] = 0f
                instOlaR[i] = 0f
            }
        }

        private fun flushByteBuffer(byteBuf: ByteBuffer, vocOut: OutputStream, instOut: OutputStream) {
            byteBuf.clear()
        }
    }
}
