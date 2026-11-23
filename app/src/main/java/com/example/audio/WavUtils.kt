package com.example.audio

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

object WavUtils {
    const val HEADER_SIZE = 44

    fun writePlaceholderHeader(out: OutputStream, sampleRate: Int, channels: Int, bitsPerSample: Int = 16) {
        val header = buildWavHeader(0L, sampleRate, channels, bitsPerSample)
        out.write(header)
    }

    fun patchWavHeader(file: File, sampleRate: Int, channels: Int, bitsPerSample: Int = 16) {
        val totalLen = file.length()
        val dataBytes = max(0L, totalLen - HEADER_SIZE)
        val header = buildWavHeader(dataBytes, sampleRate, channels, bitsPerSample)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0L)
            raf.write(header)
        }
    }

    fun buildWavHeader(
        pcmDataBytes: Long,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int = 16
    ): ByteArray {
        val byteRate = sampleRate.toLong() * channels * (bitsPerSample / 8)
        val blockAlign = (channels * (bitsPerSample / 8)).toShort()
        val clampedData = pcmDataBytes.coerceAtMost(0xFFFFFFFEL - 36L)
        val riffSize = (clampedData + 36L).toInt()
        val dataSizeInt = clampedData.toInt()

        val buf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(riffSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16) // PCM fmt chunk size
        buf.putShort(1.toShort()) // AudioFormat = 1 (PCM)
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(byteRate.toInt())
        buf.putShort(blockAlign)
        buf.putShort(bitsPerSample.toShort())
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSizeInt)
        return buf.array()
    }

    fun downsamplePeaks(rawBins: List<Float>, targetCount: Int = 110): List<Float> {
        if (rawBins.isEmpty()) return List(targetCount) { 0.12f }
        if (rawBins.size <= targetCount) {
            val maxVal = rawBins.maxOrNull()?.coerceAtLeast(0.01f) ?: 1f
            return rawBins.map { (it / maxVal).coerceIn(0.06f, 1.0f) }
        }
        val result = ArrayList<Float>(targetCount)
        val step = rawBins.size.toFloat() / targetCount
        for (i in 0 until targetCount) {
            val start = (i * step).toInt().coerceIn(0, rawBins.lastIndex)
            val end = ((i + 1) * step).toInt().coerceIn(start + 1, rawBins.size)
            var sumSq = 0f
            var peak = 0f
            for (j in start until end) {
                val v = rawBins[j]
                sumSq += v * v
                if (v > peak) peak = v
            }
            val rms = sqrt(sumSq / (end - start))
            result.add(0.55f * peak + 0.45f * rms)
        }
        val maxVal = result.maxOrNull()?.coerceAtLeast(0.01f) ?: 1f
        return result.map { (it / maxVal).coerceIn(0.06f, 1.0f) }
    }

    fun waveformToCsv(peaks: List<Float>): String =
        peaks.joinToString(",") { String.format(java.util.Locale.US, "%.3f", it) }

    fun csvToWaveform(csv: String): List<Float> {
        if (csv.isBlank()) return List(110) { 0.2f }
        return csv.split(",").mapNotNull { it.toFloatOrNull()?.coerceIn(0.04f, 1f) }
            .ifEmpty { List(110) { 0.2f } }
    }
}
