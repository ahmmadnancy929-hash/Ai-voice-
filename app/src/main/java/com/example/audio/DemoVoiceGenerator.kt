package com.example.audio

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthesizes a realistic 12-second 44.1 kHz speech-formant recording contaminated with
 * low-end fan/traffic rumble (60/120 Hz) and broadband mic static hiss, so users can
 * immediately test Before vs After studio voice enhancement on any device.
 */
object DemoVoiceGenerator {

    suspend fun createNoisyVoiceDemoUri(context: Context): Uri = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "vocal_temp").apply { mkdirs() }
        val demoFile = File(tempDir, "Noisy_Interview_Recording.wav")
        val sampleRate = 44100
        val channels = 1
        val durationSec = 12
        val totalFrames = sampleRate * durationSec
        val rng = Random(42)

        val pitchContour = doubleArrayOf(135.0, 148.0, 162.0, 142.0, 130.0, 155.0, 168.0, 138.0)

        BufferedOutputStream(FileOutputStream(demoFile), 65536).use { out ->
            val dataBytes = totalFrames.toLong() * channels * 2L
            out.write(WavUtils.buildWavHeader(dataBytes, sampleRate, channels))

            val chunkFrames = 4096
            val byteBuf = ByteBuffer.allocate(chunkFrames * 2).order(ByteOrder.LITTLE_ENDIAN)
            var frameOffset = 0
            var pinkState = 0.0

            while (frameOffset < totalFrames) {
                val count = minOf(chunkFrames, totalFrames - frameOffset)
                byteBuf.clear()

                for (i in 0 until count) {
                    val f = frameOffset + i
                    val t = f.toDouble() / sampleRate

                    // Speech-like syllable bursts (0.6s on, 0.25s pause)
                    val phraseT = t % 1.5
                    val speechActive = if (phraseT < 1.15) {
                        sin(PI * (phraseT / 1.15)).coerceIn(0.0, 1.0)
                    } else 0.0

                    val pitchIdx = ((t / 1.5).toInt()) % pitchContour.size
                    val f0 = pitchContour[pitchIdx] * (1.0 + 0.03 * sin(2.0 * PI * 4.2 * t))

                    // Muffled speech harmonics (low-mid heavy, weak presence)
                    val voice = speechActive * 0.26 * (
                        0.68 * sin(2.0 * PI * f0 * t) +
                            0.38 * sin(2.0 * PI * f0 * 2.0 * t + 0.2) +
                            0.18 * sin(2.0 * PI * f0 * 3.0 * t + 0.5) +
                            0.06 * sin(2.0 * PI * f0 * 5.0 * t)
                        )

                    // Contaminating fan/traffic hum (55 Hz + 110 Hz) + broadband static hiss
                    val fanHum = 0.14 * sin(2.0 * PI * 55.0 * t) + 0.07 * sin(2.0 * PI * 110.0 * t)
                    val white = (rng.nextDouble() * 2.0 - 1.0)
                    pinkState = 0.92 * pinkState + 0.08 * white
                    val hiss = 0.095 * white + 0.11 * pinkState

                    val sample = (voice + fanHum + hiss).coerceIn(-0.98, 0.98)
                    byteBuf.putShort((sample * 32767.0).toInt().toShort())
                }
                out.write(byteBuf.array(), 0, count * 2)
                frameOffset += count
            }
        }
        Uri.fromFile(demoFile)
    }
}
