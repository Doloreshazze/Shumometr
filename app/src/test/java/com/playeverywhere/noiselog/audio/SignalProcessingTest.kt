package com.playeverywhere.noiselog.audio

import com.playeverywhere.noiselog.data.LevelAccumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SignalProcessingTest {
    @Test
    fun fftFindsOneKilohertzTone() {
        val sampleRate = 48_000
        val size = 4096
        val samples = ShortArray(size) { index ->
            (sin(2.0 * PI * 1_000.0 * index / sampleRate) * 12_000).toInt().toShort()
        }
        val result = AudioAnalyzer(sampleRate).analyze(samples, samples.size, 0.0)
        assertTrue(result.dominantHz in 980.0..1_020.0)
        assertEquals(72, result.spectrum.size)
        assertEquals(240, result.waveform.size)
        assertTrue(result.dbA.isFinite())
    }

    @Test
    fun analyzerCanReuseBuffersForDifferentFrequencies() {
        val sampleRate = 48_000
        val size = 4096
        val analyzer = AudioAnalyzer(sampleRate)
        val oneKhz = ShortArray(size) { index ->
            (sin(2.0 * PI * 1_000.0 * index / sampleRate) * 12_000).toInt().toShort()
        }
        val twoKhz = ShortArray(size) { index ->
            (sin(2.0 * PI * 2_000.0 * index / sampleRate) * 12_000).toInt().toShort()
        }

        assertTrue(analyzer.analyze(oneKhz, size, 0.0).dominantHz in 980.0..1_020.0)
        assertTrue(analyzer.analyze(twoKhz, size, 0.0).dominantHz in 1_980.0..2_020.0)
    }

    @Test
    fun accumulatorUsesEnergyForLeq() {
        val accumulator = LevelAccumulator()
        accumulator.add(40.0, 100.0)
        accumulator.add(80.0, 300.0)
        val result = requireNotNull(accumulator.snapshot())
        assertEquals(40.0, result.minDb, 0.001)
        assertEquals(80.0, result.maxDb, 0.001)
        assertEquals(60.0, result.avgDb, 0.001)
        assertTrue(result.leqDb > result.avgDb)
        assertEquals(200.0, result.dominantHz, 0.001)
    }

    @Test
    fun voiceSegmenterBoundsContinuousSpeech() {
        val segmenter = VoiceSegmenter(48_000, 16_000)
        val loudFrame = ShortArray(4_800) { 12_000 }
        var timestamp = 1_000_000L
        var segment: VoiceSegmenter.Segment? = null

        repeat(70) {
            segment = segment ?: segmenter.accept(loudFrame, loudFrame.size, -12.0, timestamp)
            timestamp += 100L
        }

        val result = requireNotNull(segment)
        assertTrue(result.samples.isNotEmpty())
        assertTrue(result.samples.size <= 16_000 * 5)
    }

    @Test
    fun voiceSegmenterFlushesPcmWithoutBoxing() {
        val segmenter = VoiceSegmenter(48_000, 16_000)
        val loudFrame = ShortArray(4_800) { 8_000 }
        repeat(10) { index ->
            segmenter.accept(loudFrame, loudFrame.size, -18.0, 2_000_000L + index * 100L)
        }

        val result = requireNotNull(segmenter.flush())
        assertTrue(result.samples.size >= 8_000)
        assertEquals(ShortArray::class, result.samples::class)
    }
}
