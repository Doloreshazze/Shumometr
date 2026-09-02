package com.playeverywhere.noiselog.data

data class SessionSummary(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val minDb: Double,
    val maxDb: Double,
    val avgDb: Double,
    val leqDb: Double,
    val dominantHz: Double,
    val transcriptCount: Int
)

data class TranscriptEntry(
    val id: Long,
    val sessionId: Long,
    val timestamp: Long,
    val language: String,
    val text: String
)

data class PeriodSummary(
    val minutes: Int = 0,
    val minDb: Double = 0.0,
    val maxDb: Double = 0.0,
    val leqDb: Double = 0.0,
    val transcriptCount: Int = 0
)

data class MinuteAggregate(
    val startedAt: Long,
    val minDb: Double,
    val maxDb: Double,
    val avgDb: Double,
    val leqDb: Double,
    val dominantHz: Double,
    val samples: Long
)

class LevelAccumulator {
    private var count = 0L
    private var sumDb = 0.0
    private var sumLinear = 0.0
    private var weightedDominant = 0.0
    private var minDb = Double.POSITIVE_INFINITY
    private var maxDb = Double.NEGATIVE_INFINITY
    private var startedAt = System.currentTimeMillis()

    fun add(db: Double, dominantHz: Double) {
        if (!db.isFinite()) return
        count++
        sumDb += db
        sumLinear += Math.pow(10.0, db / 10.0)
        weightedDominant += dominantHz
        minDb = minOf(minDb, db)
        maxDb = maxOf(maxDb, db)
    }

    fun snapshot(): MinuteAggregate? {
        if (count == 0L) return null
        return MinuteAggregate(
            startedAt = startedAt,
            minDb = minDb,
            maxDb = maxDb,
            avgDb = sumDb / count,
            leqDb = 10.0 * kotlin.math.log10(sumLinear / count),
            dominantHz = weightedDominant / count,
            samples = count
        )
    }

    fun snapshotAndReset(now: Long = System.currentTimeMillis()): MinuteAggregate? {
        val result = snapshot()
        count = 0L
        sumDb = 0.0
        sumLinear = 0.0
        weightedDominant = 0.0
        minDb = Double.POSITIVE_INFINITY
        maxDb = Double.NEGATIVE_INFINITY
        startedAt = now
        return result
    }
}
