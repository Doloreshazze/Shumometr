package com.playeverywhere.noiselog.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

data class FrameAnalysis(
    val dbA: Double,
    val dbUnweighted: Double,
    val dominantHz: Double,
    val waveform: FloatArray,
    val spectrum: FloatArray,
    val spectrumFrequencies: FloatArray,
    val dbFs: Double
)

class AudioAnalyzer(
    private val sampleRate: Int,
    private val fftSize: Int = 4096,
    private val spectrumBands: Int = 72
) {
    private val window = DoubleArray(fftSize) { index ->
        0.5 - 0.5 * kotlin.math.cos(2.0 * PI * index / (fftSize - 1))
    }
    private val frequencyAxis = FloatArray(spectrumBands) { band ->
        val ratio = band.toDouble() / (spectrumBands - 1).coerceAtLeast(1)
        (20.0 * (20_000.0 / 20.0).pow(ratio)).toFloat()
    }
    private val real = DoubleArray(fftSize)
    private val imag = DoubleArray(fftSize)
    private val magnitudes = DoubleArray(fftSize / 2)
    private val aWeightByBin = DoubleArray(fftSize / 2) { bin ->
        aWeightPower(bin.toDouble() * sampleRate / fftSize)
    }

    fun analyze(samples: ShortArray, count: Int, calibrationDb: Double): FrameAnalysis {
        val usable = minOf(count, samples.size)
        var sumSquares = 0.0
        for (i in 0 until usable) {
            val normalized = samples[i] / 32768.0
            sumSquares += normalized * normalized
        }
        val meanSquare = (sumSquares / usable.coerceAtLeast(1)).coerceAtLeast(1e-12)
        val dbFs = 10.0 * log10(meanSquare)

        imag.fill(0.0)
        val offset = (usable - fftSize).coerceAtLeast(0)
        for (i in 0 until fftSize) {
            val sampleIndex = offset + i
            val value = if (sampleIndex < usable) samples[sampleIndex] / 32768.0 else 0.0
            real[i] = value * window[i]
        }
        Fft.transform(real, imag)

        magnitudes[0] = 0.0
        var totalSpectralPower = 0.0
        var aWeightedPower = 0.0
        var dominantBin = 1
        var dominantMagnitude = 0.0
        for (bin in 1 until fftSize / 2) {
            val magnitudeSquared = real[bin] * real[bin] + imag[bin] * imag[bin]
            magnitudes[bin] = sqrt(magnitudeSquared)
            totalSpectralPower += magnitudeSquared
            val frequency = bin.toDouble() * sampleRate / fftSize
            aWeightedPower += magnitudeSquared * aWeightByBin[bin]
            if (frequency in 45.0..18_000.0 && magnitudeSquared > dominantMagnitude) {
                dominantMagnitude = magnitudeSquared
                dominantBin = bin
            }
        }

        val weightedRatio = if (totalSpectralPower > 1e-20) {
            (aWeightedPower / totalSpectralPower).coerceIn(1e-6, 4.0)
        } else 1e-6
        val referenceOffset = 120.0 + calibrationDb
        val dbUnweighted = (dbFs + referenceOffset).coerceIn(0.0, 140.0)
        val dbA = (10.0 * log10(meanSquare * weightedRatio) + referenceOffset).coerceIn(0.0, 140.0)

        val spectrum = FloatArray(spectrumBands)
        for (band in 0 until spectrumBands) {
            val center = frequencyAxis[band].toDouble()
            val next = if (band + 1 < spectrumBands) frequencyAxis[band + 1].toDouble() else 20_000.0
            val previous = if (band > 0) frequencyAxis[band - 1].toDouble() else 20.0
            val low = sqrt(previous * center)
            val high = sqrt(center * next)
            val lowBin = (low * fftSize / sampleRate).toInt().coerceIn(1, magnitudes.lastIndex)
            val highBin = (high * fftSize / sampleRate).toInt().coerceIn(lowBin, magnitudes.lastIndex)
            var peak = 0.0
            for (bin in lowBin..highBin) peak = maxOf(peak, magnitudes[bin])
            val amplitude = (peak * 2.0 / fftSize / 0.5).coerceAtLeast(1e-9)
            spectrum[band] = (20.0 * log10(amplitude) + referenceOffset).coerceIn(0.0, 140.0).toFloat()
        }

        val waveformPoints = 240
        val waveform = FloatArray(waveformPoints)
        val step = usable.toDouble() / waveformPoints
        for (point in 0 until waveformPoints) {
            val start = (point * step).toInt().coerceAtMost((usable - 1).coerceAtLeast(0))
            val end = ((point + 1) * step).toInt().coerceIn(start + 1, usable.coerceAtLeast(start + 1))
            var peak = 0
            var signedPeak = 0
            for (i in start until minOf(end, usable)) {
                val candidate = samples[i].toInt()
                if (abs(candidate) > peak) {
                    peak = abs(candidate)
                    signedPeak = candidate
                }
            }
            waveform[point] = signedPeak / 32768f
        }

        return FrameAnalysis(
            dbA = dbA,
            dbUnweighted = dbUnweighted,
            dominantHz = dominantBin.toDouble() * sampleRate / fftSize,
            waveform = waveform,
            spectrum = spectrum,
            spectrumFrequencies = frequencyAxis,
            dbFs = dbFs
        )
    }

    private fun aWeightPower(frequency: Double): Double {
        if (frequency <= 0.0) return 0.0
        val f2 = frequency * frequency
        val numerator = (12_194.0.pow(2) * f2 * f2)
        val denominator =
            (f2 + 20.6.pow(2)) *
                sqrt((f2 + 107.7.pow(2)) * (f2 + 737.9.pow(2))) *
                (f2 + 12_194.0.pow(2))
        val ra = numerator / denominator
        val aDb = 20.0 * log10(ra.coerceAtLeast(1e-12)) + 2.0
        return 10.0.pow(aDb / 10.0)
    }
}
