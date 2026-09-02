package com.playeverywhere.noiselog.audio

/**
 * Lightweight adaptive voice activity gate. It never writes raw audio to disk;
 * it only holds one short PCM segment in primitive in-memory buffers.
 */
class VoiceSegmenter(
    private val inputSampleRate: Int = 48_000,
    private val outputSampleRate: Int = 16_000
) {
    data class Segment(val startedAt: Long, val samples: ShortArray)

    companion object {
        private const val MAX_SEGMENT_SECONDS = 5
    }

    private val ratio = (inputSampleRate / outputSampleRate).coerceAtLeast(1)
    private val preRoll = ShortArray(outputSampleRate)
    private val speech = ShortArray(outputSampleRate * MAX_SEGMENT_SECONDS)
    private var preRollSize = 0
    private var preRollWrite = 0
    private var speechSize = 0
    private var noiseFloorDbFs = -58.0
    private var speaking = false
    private var silenceSamples = 0
    private var startedAt = 0L

    fun accept(
        source: ShortArray,
        count: Int,
        dbFs: Double,
        timestamp: Long
    ): Segment? {
        val outputCount = count.coerceAtMost(source.size) / ratio
        val threshold = maxOf(-52.0, noiseFloorDbFs + 8.0)
        val voiceNow = dbFs > threshold

        if (!speaking) {
            noiseFloorDbFs = noiseFloorDbFs * 0.985 + dbFs.coerceAtMost(-20.0) * 0.015
            appendToPreRoll(source, outputCount)
            if (voiceNow) {
                speaking = true
                startedAt = timestamp - (preRollSize * 1000L / outputSampleRate)
                copyPreRollToSpeech()
                silenceSamples = 0
            }
            return null
        }

        appendToSpeech(source, outputCount)
        silenceSamples = if (voiceNow) 0 else silenceSamples + outputCount

        val enoughSilence = silenceSamples >= (outputSampleRate * 0.6).toInt()
        val tooLong = speechSize >= speech.size
        if (!enoughSilence && !tooLong) return null

        val result = createSegment(minimumSamples = (outputSampleRate * 0.5).toInt())
        resetSegment()
        return result
    }

    fun flush(): Segment? {
        if (!speaking) return null
        val result = createSegment(minimumSamples = outputSampleRate / 2)
        resetSegment()
        return result
    }

    private fun appendToPreRoll(source: ShortArray, outputCount: Int) {
        for (index in 0 until outputCount) {
            preRoll[preRollWrite] = downsample(source, index)
            preRollWrite = (preRollWrite + 1) % preRoll.size
            if (preRollSize < preRoll.size) preRollSize++
        }
    }

    private fun copyPreRollToSpeech() {
        speechSize = 0
        var read = (preRollWrite - preRollSize + preRoll.size) % preRoll.size
        repeat(preRollSize.coerceAtMost(speech.size)) {
            speech[speechSize++] = preRoll[read]
            read = (read + 1) % preRoll.size
        }
    }

    private fun appendToSpeech(source: ShortArray, outputCount: Int) {
        for (index in 0 until outputCount) {
            if (speechSize >= speech.size) return
            speech[speechSize++] = downsample(source, index)
        }
    }

    private fun downsample(source: ShortArray, outputIndex: Int): Short {
        var sum = 0
        val start = outputIndex * ratio
        for (part in 0 until ratio) sum += source[start + part].toInt()
        return (sum / ratio).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    private fun createSegment(minimumSamples: Int): Segment? =
        if (speechSize >= minimumSamples) Segment(startedAt, speech.copyOf(speechSize)) else null

    private fun resetSegment() {
        speaking = false
        silenceSamples = 0
        speechSize = 0
        preRollSize = 0
        preRollWrite = 0
    }
}
