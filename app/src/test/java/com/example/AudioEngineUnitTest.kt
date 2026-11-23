package com.example

import com.example.audio.FftProcessor
import com.example.audio.WavUtils
import com.example.i18n.AppLanguage
import com.example.i18n.AppLocalization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioEngineUnitTest {

    @Test
    fun fftAndIfftRoundTripRestoresSignal() {
        val n = 2048
        val fft = FftProcessor(n)
        val original = FloatArray(n) { i ->
            (0.6 * sin(2.0 * PI * 440.0 * i / 44100.0) +
                0.3 * sin(2.0 * PI * 1200.0 * i / 44100.0)).toFloat()
        }
        val real = original.copyOf()
        val imag = FloatArray(n)

        fft.fft(real, imag)
        fft.ifft(real, imag)

        var maxErr = 0f
        for (i in 0 until n) {
            val err = abs(real[i] - original[i])
            if (err > maxErr) maxErr = err
        }
        assertTrue("Expected max FFT/IFFT reconstruction error < 1e-4, was $maxErr", maxErr < 1e-4f)
    }

    @Test
    fun wavHeaderEncodesCorrectPcmMetadata() {
        val header = WavUtils.buildWavHeader(pcmDataBytes = 88200L, sampleRate = 44100, channels = 2)
        assertEquals(WavUtils.HEADER_SIZE, header.size)
        assertEquals("RIFF", String(header, 0, 4))
        assertEquals("WAVE", String(header, 8, 4))
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2, bb.getShort(22).toInt())
        assertEquals(44100, bb.getInt(24))
        assertEquals(88200, bb.getInt(40))
    }

    @Test
    fun downsamplePeaksNormalizesToTargetCount() {
        val raw = List(500) { i -> (i % 50) / 50f }
        val peaks = WavUtils.downsamplePeaks(raw, targetCount = 100)
        assertEquals(100, peaks.size)
        assertTrue(peaks.all { it in 0.05f..1.0f })
    }

    @Test
    fun bilingualLocalizationProvidesDistinctEnglishAndBanglaStrings() {
        val en = AppLocalization.forLanguage(AppLanguage.ENGLISH)
        val bn = AppLocalization.forLanguage(AppLanguage.BANGLA)
        assertEquals("AI Vocal Remover Pro", en.appTitle)
        assertEquals("AI Voice Enhancer", en.navVoiceEnhancer)
        assertTrue(bn.appTitle.isNotBlank() && bn.appTitle != en.appTitle)
        assertTrue(bn.uploadAudioBtn.isNotBlank() && bn.uploadAudioBtn != en.uploadAudioBtn)
        assertTrue(bn.navVoiceEnhancer.isNotBlank() && bn.navVoiceEnhancer != en.navVoiceEnhancer)
        assertTrue(bn.veStartBtn.isNotBlank() && bn.veStartBtn != en.veStartBtn)
    }

    @Test
    fun enhancementStrengthLevelsAreOrderedMonotonically() {
        val levels = com.example.audio.EnhancementStrength.entries
        assertEquals(4, levels.size)
        for (i in 1 until levels.size) {
            assertTrue(levels[i].normalized > levels[i - 1].normalized)
        }
    }
}
