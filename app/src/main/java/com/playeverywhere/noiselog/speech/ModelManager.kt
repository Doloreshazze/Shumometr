package com.playeverywhere.noiselog.speech

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File

object ModelManager {
    private const val PREFS = "speech_model"
    private const val MODEL_ID = "model_download_id"
    private const val TOKENS_ID = "tokens_download_id"
    private const val MODEL_MIN_BYTES = 340_000_000L
    private const val TOKENS_MIN_BYTES = 50_000L

    private const val REPOSITORY =
        "https://huggingface.co/csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12/resolve/main"

    data class Status(
        val ready: Boolean,
        val downloading: Boolean,
        val progress: Int,
        val message: String
    )

    fun modelDirectory(context: Context): File = File(
        requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)),
        "models/omnilingual"
    )

    fun modelFile(context: Context) = File(modelDirectory(context), "model.int8.onnx")
    fun tokensFile(context: Context) = File(modelDirectory(context), "tokens.txt")

    fun isReady(context: Context): Boolean =
        modelFile(context).length() >= MODEL_MIN_BYTES && tokensFile(context).length() >= TOKENS_MIN_BYTES

    fun enqueue(context: Context): Boolean {
        if (isReady(context)) return true
        val directory = modelDirectory(context)
        directory.mkdirs()
        val manager = context.getSystemService(DownloadManager::class.java) ?: return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val modelRequest = DownloadManager.Request(Uri.parse("$REPOSITORY/model.int8.onnx?download=true"))
            .setTitle("Шумограф: языковая модель")
            .setDescription("Офлайн-распознавание речи на 1600+ языках")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                "models/omnilingual/model.int8.onnx"
            )

        val tokensRequest = DownloadManager.Request(Uri.parse("$REPOSITORY/tokens.txt?download=true"))
            .setTitle("Шумограф: словарь модели")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                "models/omnilingual/tokens.txt"
            )

        return try {
            if (modelFile(context).exists() && modelFile(context).length() < MODEL_MIN_BYTES) modelFile(context).delete()
            if (tokensFile(context).exists() && tokensFile(context).length() < TOKENS_MIN_BYTES) tokensFile(context).delete()
            val modelId = manager.enqueue(modelRequest)
            val tokensId = manager.enqueue(tokensRequest)
            prefs.edit().putLong(MODEL_ID, modelId).putLong(TOKENS_ID, tokensId).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun status(context: Context): Status {
        if (isReady(context)) return Status(true, false, 100, "Офлайн-модель готова")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = longArrayOf(prefs.getLong(MODEL_ID, -1), prefs.getLong(TOKENS_ID, -1)).filter { it > 0 }
        if (ids.isEmpty()) return Status(false, false, 0, "Модель 1600+ языков не загружена · 365 МБ")

        val manager = context.getSystemService(DownloadManager::class.java)
            ?: return Status(false, false, 0, "Служба загрузок недоступна")
        var downloaded = 0L
        var total = 0L
        var active = false
        var failed = false
        manager.query(DownloadManager.Query().setFilterById(*ids.toLongArray())).use { cursor ->
            val statusColumn = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val downloadedColumn = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalColumn = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            while (cursor.moveToNext()) {
                val itemStatus = cursor.getInt(statusColumn)
                active = active || itemStatus == DownloadManager.STATUS_RUNNING || itemStatus == DownloadManager.STATUS_PENDING || itemStatus == DownloadManager.STATUS_PAUSED
                failed = failed || itemStatus == DownloadManager.STATUS_FAILED
                downloaded += cursor.getLong(downloadedColumn).coerceAtLeast(0)
                total += cursor.getLong(totalColumn).coerceAtLeast(0)
            }
        }
        val progress = if (total > 0) (downloaded * 100 / total).toInt().coerceIn(0, 99) else 0
        return when {
            active -> Status(false, true, progress, "Загрузка офлайн-модели · $progress%")
            failed -> Status(false, false, progress, "Загрузка прервана — нажмите, чтобы повторить")
            else -> Status(false, false, progress, "Проверка файлов модели…")
        }
    }

    fun delete(context: Context): Boolean {
        val modelDeleted = !modelFile(context).exists() || modelFile(context).delete()
        val tokensDeleted = !tokensFile(context).exists() || tokensFile(context).delete()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        return modelDeleted && tokensDeleted
    }
}
