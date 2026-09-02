package com.playeverywhere.noiselog.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place radix-2 FFT used by the live spectrum and A-weighting estimate. */
object Fft {
    fun transform(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        require(n == imag.size && n > 0 && n and (n - 1) == 0) { "FFT size must be a power of two" }

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]
                real[i] = real[j]
                real[j] = tr
                val ti = imag[i]
                imag[i] = imag[j]
                imag[j] = ti
            }
        }

        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val wLenR = cos(angle)
            val wLenI = sin(angle)
            var start = 0
            while (start < n) {
                var wr = 1.0
                var wi = 0.0
                for (offset in 0 until length / 2) {
                    val even = start + offset
                    val odd = even + length / 2
                    val oddR = real[odd] * wr - imag[odd] * wi
                    val oddI = real[odd] * wi + imag[odd] * wr
                    val evenR = real[even]
                    val evenI = imag[even]
                    real[even] = evenR + oddR
                    imag[even] = evenI + oddI
                    real[odd] = evenR - oddR
                    imag[odd] = evenI - oddI
                    val nextWr = wr * wLenR - wi * wLenI
                    wi = wr * wLenI + wi * wLenR
                    wr = nextWr
                }
                start += length
            }
            length = length shl 1
        }
    }
}
