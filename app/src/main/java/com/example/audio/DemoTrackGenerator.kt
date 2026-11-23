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
import kotlin.math.exp
import kotlin.math.sin

/**
 * Generates a real 12-second 44.1 kHz stereo WAV test session combining:
 * - Center-panned harmonic vocal-formant melody with vibrato
 * - Wide-panned stereo synth chord progressions, warm bassline, and beat pulses
 * so users can immediately test real vocal/instrumental separation on any device.
 */
object DemoTrackGenerator {

    suspend fun createDemoAudioUri(context: Context): Uri = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "vocal_temp").apply { mkdirs() }
        val demoFile = File(tempDir, "Studio_Demo_Session.wav")
        val sampleRate = 44100
        val channels = 2
        val durationSec = 12
        val totalFrames = sampleRate * durationSec

        val vocalNotes = doubleArrayOf(261.63, 293.66, 329.63, 392.00, 329.63, 293.66, 261.63, 349.23)
        val chordRoots = doubleArrayOf(130.81, 164.81, 196.00, 146.83)

        BufferedOutputStream(FileOutputStream(demoFile), 65536).use { out ->
            val dataBytes = totalFrames.toLong() * channels * 2L
            out.write(WavUtils.buildWavHeader(dataBytes, sampleRate, channels))

            val chunkFrames = 4096
            val byteBuf = ByteBuffer.allocate(chunkFrames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)

            var frameOffset = 0
            while (frameOffset < totalFrames) {
                val count = minOf(chunkFrames, totalFrames - frameOffset)
                byteBuf.clear()

                for (i in 0 until count) {
                    val f = frameOffset + i
                    val t = f.toDouble() / sampleRate

                    // 1. Center-panned expressive vocal-formant melody (1.5s per phrase)
                    val noteIdx = ((t / 1.5).toInt()) % vocalNotes.size
                    val noteLocalT = t % 1.5
                    val env = sin(PI * (noteLocalT / 1.5)).coerceIn(0.0, 1.0)
                    val vibrato = 1.0 + 0.008 * sin(2.0 * PI * 5.5 * t)
                    val f0 = vocalNotes[noteIdx] * vibrato
                    val vocal = env * 0.42 * (
                        0.60 * sin(2.0 * PI * f0 * t) +
                            0.28 * sin(2.0 * PI * f0 * 2.0 * t + 0.3) +
                            0.14 * sin(2.0 * PI * f0 * 3.0 * t + 0.7) +
                            0.08 * sin(2.0 * PI * f0 * 4.0 * t)
                        )

                    // 2. Wide stereo chord pads + sub-bass + rhythmic pulse
                    val chordIdx = ((t / 3.0).toInt()) % chordRoots.size
                    val root = chordRoots[chordIdx]
                    val bass = 0.25 * sin(2.0 * PI * (root * 0.5) * t)

                    val beatT = t % 0.5
                    val kick = if (beatT < 0.14) {
                        0.30 * exp(-beatT * 24.0) * sin(2.0 * PI * (110.0 - beatT * 380.0) * beatT)
                    } else 0.0

                    // Stereo-spread pads (phase-shifted between L & R)
                    val padL = 0.16 * (
                        sin(2.0 * PI * root * 2.0 * t) +
                            0.8 * sin(2.0 * PI * root * 2.5 * t + 0.8) +
                            0.6 * sin(2.0 * PI * root * 3.0 * t + 1.4)
                        )
                    val padR = 0.16 * (
                        sin(2.0 * PI * root * 2.0 * t + 1.1) +
                            0.8 * sin(2.0 * PI * root * 2.5 * t + 2.1) +
                            0.6 * sin(2.0 * PI * root * 3.0 * t + 2.9)
                        )

                    val left = (vocal + bass + kick + padL).coerceIn(-0.98, 0.98)
                    val right = (vocal + bass + kick + padR).coerceIn(-0.98, 0.98)

                    byteBuf.putShort((left * 32767.0).toInt().toShort())
                    byteBuf.putShort((right * 32767.0).toInt().toShort())
                }
                out.write(byteBuf.array(), 0, count * channels * 2)
                frameOffset += count
            }
        }
        Uri.fromFile(demoFile)
    }
}
