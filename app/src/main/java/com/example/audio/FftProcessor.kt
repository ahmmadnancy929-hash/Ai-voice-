package com.example.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin in-place Radix-2 Cooley-Tukey FFT/IFFT processor with precomputed
 * twiddle factors and sqrt-Hann window for lossless WOLA (Weighted Overlap-Add).
 */
class FftProcessor(val fftSize: Int = 2048) {

    init {
        require(fftSize > 0 && (fftSize and (fftSize - 1)) == 0) {
            "fftSize must be a power of 2"
        }
    }

    private val cosTable = FloatArray(fftSize / 2)
    private val sinTable = FloatArray(fftSize / 2)
    private val bitReverse = IntArray(fftSize)
    val sqrtHannWindow = FloatArray(fftSize)

    init {
        val levels = Integer.numberOfTrailingZeros(fftSize)
        for (i in 0 until fftSize / 2) {
            val angle = -2.0 * PI * i / fftSize
            cosTable[i] = cos(angle).toFloat()
            sinTable[i] = sin(angle).toFloat()
        }
        for (i in 0 until fftSize) {
            bitReverse[i] = Integer.reverse(i) ushr (32 - levels)
            // Periodic sqrt-Hann window gives unity sum at 75% overlap (hop = fftSize / 4)
            val hann = 0.5 * (1.0 - cos(2.0 * PI * i / fftSize))
            sqrtHannWindow[i] = sqrt(hann).toFloat()
        }
    }

    fun fft(real: FloatArray, imag: FloatArray) {
        transform(real, imag, inverse = false)
    }

    fun ifft(real: FloatArray, imag: FloatArray) {
        transform(real, imag, inverse = true)
        val invN = 1.0f / fftSize
        for (i in 0 until fftSize) {
            real[i] *= invN
            imag[i] *= invN
        }
    }

    private fun transform(real: FloatArray, imag: FloatArray, inverse: Boolean) {
        for (i in 0 until fftSize) {
            val j = bitReverse[i]
            if (j > i) {
                val tr = real[i]
                real[i] = real[j]
                real[j] = tr
                val ti = imag[i]
                imag[i] = imag[j]
                imag[j] = ti
            }
        }

        var size = 2
        while (size <= fftSize) {
            val halfSize = size ushr 1
            val tableStep = fftSize / size
            var i = 0
            while (i < fftSize) {
                var k = 0
                for (j in i until i + halfSize) {
                    val c = cosTable[k]
                    val s = if (inverse) -sinTable[k] else sinTable[k]
                    val idx = j + halfSize
                    val tpre = real[idx] * c - imag[idx] * s
                    val tpim = real[idx] * s + imag[idx] * c
                    real[idx] = real[j] - tpre
                    imag[idx] = imag[j] - tpim
                    real[j] += tpre
                    imag[j] += tpim
                    k += tableStep
                }
                i += size
            }
            size = size shl 1
        }
    }
}
