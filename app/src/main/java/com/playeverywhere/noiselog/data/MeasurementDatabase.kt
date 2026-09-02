package com.playeverywhere.noiselog.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MeasurementDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "noise_log.db", null, VERSION) {

    companion object {
        private const val VERSION = 1
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                min_db REAL NOT NULL DEFAULT 0,
                max_db REAL NOT NULL DEFAULT 0,
                avg_db REAL NOT NULL DEFAULT 0,
                leq_db REAL NOT NULL DEFAULT 0,
                dominant_hz REAL NOT NULL DEFAULT 0,
                samples_count INTEGER NOT NULL DEFAULT 0,
                linear_energy_sum REAL NOT NULL DEFAULT 0,
                transcript_count INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE minute_stats (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                started_at INTEGER NOT NULL,
                min_db REAL NOT NULL,
                max_db REAL NOT NULL,
                avg_db REAL NOT NULL,
                leq_db REAL NOT NULL,
                dominant_hz REAL NOT NULL,
                samples_count INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_minute_time ON minute_stats(started_at)")
        db.execSQL("CREATE INDEX idx_minute_session ON minute_stats(session_id)")
        db.execSQL(
            """
            CREATE TABLE transcripts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                timestamp INTEGER NOT NULL,
                language TEXT NOT NULL DEFAULT '',
                text TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_transcript_time ON transcripts(timestamp)")
        db.execSQL("CREATE INDEX idx_transcript_session ON transcripts(session_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun startSession(startedAt: Long = System.currentTimeMillis()): Long {
        val stale = ContentValues().apply { put("ended_at", startedAt) }
        writableDatabase.update("sessions", stale, "ended_at IS NULL", null)
        val values = ContentValues().apply { put("started_at", startedAt) }
        return writableDatabase.insertOrThrow("sessions", null, values)
    }

    @Synchronized
    fun appendMinute(sessionId: Long, value: MinuteAggregate) {
        val db = writableDatabase
        db.beginTransaction()
        try {
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("started_at", value.startedAt)
            put("min_db", value.minDb)
            put("max_db", value.maxDb)
            put("avg_db", value.avgDb)
            put("leq_db", value.leqDb)
            put("dominant_hz", value.dominantHz)
            put("samples_count", value.samples)
        }
            db.insertOrThrow("minute_stats", null, values)

            var oldMin = Double.POSITIVE_INFINITY
            var oldMax = Double.NEGATIVE_INFINITY
            var oldAverage = 0.0
            var oldDominant = 0.0
            var oldSamples = 0L
            var oldLinear = 0.0
            db.rawQuery(
                "SELECT min_db,max_db,avg_db,dominant_hz,samples_count,linear_energy_sum FROM sessions WHERE id=?",
                arrayOf(sessionId.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    oldMin = if (cursor.getLong(4) == 0L) Double.POSITIVE_INFINITY else cursor.getDouble(0)
                    oldMax = if (cursor.getLong(4) == 0L) Double.NEGATIVE_INFINITY else cursor.getDouble(1)
                    oldAverage = cursor.getDouble(2)
                    oldDominant = cursor.getDouble(3)
                    oldSamples = cursor.getLong(4)
                    oldLinear = cursor.getDouble(5)
                }
            }
            val totalSamples = oldSamples + value.samples
            val linear = oldLinear + Math.pow(10.0, value.leqDb / 10.0) * value.samples
            val sessionValues = ContentValues().apply {
                put("min_db", minOf(oldMin, value.minDb))
                put("max_db", maxOf(oldMax, value.maxDb))
                put("avg_db", (oldAverage * oldSamples + value.avgDb * value.samples) / totalSamples)
                put("leq_db", 10.0 * kotlin.math.log10(linear / totalSamples))
                put("dominant_hz", (oldDominant * oldSamples + value.dominantHz * value.samples) / totalSamples)
                put("samples_count", totalSamples)
                put("linear_energy_sum", linear)
            }
            db.update("sessions", sessionValues, "id=?", arrayOf(sessionId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun appendTranscript(
        sessionId: Long,
        timestamp: Long,
        text: String,
        language: String = ""
    ) {
        if (text.isBlank()) return
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("timestamp", timestamp)
            put("language", language)
            put("text", text.trim())
        }
        writableDatabase.insertOrThrow("transcripts", null, values)
        writableDatabase.execSQL(
            "UPDATE sessions SET transcript_count = transcript_count + 1 WHERE id = ?",
            arrayOf(sessionId)
        )
    }

    @Synchronized
    fun finishSession(sessionId: Long, endedAt: Long = System.currentTimeMillis()) {
        val values = ContentValues().apply { put("ended_at", endedAt) }
        writableDatabase.update("sessions", values, "id=?", arrayOf(sessionId.toString()))
    }

    @Synchronized
    fun recentSessions(limit: Int = 30): List<SessionSummary> {
        val out = ArrayList<SessionSummary>()
        readableDatabase.rawQuery(
            "SELECT id,started_at,ended_at,min_db,max_db,avg_db,leq_db,dominant_hz,transcript_count FROM sessions ORDER BY started_at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) out += cursor.toSessionSummary()
        }
        return out
    }

    @Synchronized
    fun recentTranscripts(limit: Int = 100): List<TranscriptEntry> {
        val out = ArrayList<TranscriptEntry>()
        readableDatabase.rawQuery(
            "SELECT id,session_id,timestamp,language,text FROM transcripts ORDER BY timestamp DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += TranscriptEntry(
                    id = cursor.getLong(0),
                    sessionId = cursor.getLong(1),
                    timestamp = cursor.getLong(2),
                    language = cursor.getString(3).orEmpty(),
                    text = cursor.getString(4).orEmpty()
                )
            }
        }
        return out
    }

    @Synchronized
    fun periodSummary(since: Long): PeriodSummary {
        var minutes = 0
        var minDb = Double.POSITIVE_INFINITY
        var maxDb = Double.NEGATIVE_INFINITY
        var linear = 0.0
        var samples = 0L
        readableDatabase.rawQuery(
            "SELECT min_db,max_db,leq_db,samples_count FROM minute_stats WHERE started_at>=?",
            arrayOf(since.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                minutes++
                minDb = minOf(minDb, cursor.getDouble(0))
                maxDb = maxOf(maxDb, cursor.getDouble(1))
                val n = cursor.getLong(3).coerceAtLeast(1)
                linear += Math.pow(10.0, cursor.getDouble(2) / 10.0) * n
                samples += n
            }
        }
        val transcriptCount = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM transcripts WHERE timestamp>=?",
            arrayOf(since.toString())
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        return if (minutes == 0) PeriodSummary(transcriptCount = transcriptCount) else PeriodSummary(
            minutes,
            minDb,
            maxDb,
            10.0 * kotlin.math.log10(linear / samples.coerceAtLeast(1)),
            transcriptCount
        )
    }

    @Synchronized
    fun cleanup(retentionDays: Int) {
        if (retentionDays <= 0) return
        val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
        writableDatabase.delete("sessions", "started_at < ?", arrayOf(cutoff.toString()))
    }

    @Synchronized
    fun exportCsv(output: OutputStream) {
        val writer = OutputStreamWriter(output, Charsets.UTF_8)
        writer.write("type,timestamp,session_id,min_db,max_db,avg_db,leq_db,dominant_hz,language,text\n")
        readableDatabase.rawQuery(
            "SELECT session_id,started_at,min_db,max_db,avg_db,leq_db,dominant_hz FROM minute_stats ORDER BY started_at",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                writer.write(
                    listOf(
                        "minute",
                        iso(cursor.getLong(1)),
                        cursor.getLong(0).toString(),
                        format(cursor.getDouble(2)),
                        format(cursor.getDouble(3)),
                        format(cursor.getDouble(4)),
                        format(cursor.getDouble(5)),
                        format(cursor.getDouble(6)),
                        "",
                        ""
                    ).joinToString(",") + "\n"
                )
            }
        }
        readableDatabase.rawQuery(
            "SELECT session_id,timestamp,language,text FROM transcripts ORDER BY timestamp",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                writer.write(
                    listOf(
                        "transcript",
                        iso(cursor.getLong(1)),
                        cursor.getLong(0).toString(),
                        "", "", "", "", "",
                        csv(cursor.getString(2).orEmpty()),
                        csv(cursor.getString(3).orEmpty())
                    ).joinToString(",") + "\n"
                )
            }
        }
        writer.flush()
    }

    private fun Cursor.toSessionSummary() = SessionSummary(
        id = getLong(0),
        startedAt = getLong(1),
        endedAt = if (isNull(2)) null else getLong(2),
        minDb = getDouble(3),
        maxDb = getDouble(4),
        avgDb = getDouble(5),
        leqDb = getDouble(6),
        dominantHz = getDouble(7),
        transcriptCount = getInt(8)
    )

    private fun format(value: Double) = String.format(Locale.US, "%.2f", value)
    private fun iso(timestamp: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(timestamp))
    private fun csv(value: String) = "\"${value.replace("\"", "\"\"").replace("\n", " ")}\""
}
