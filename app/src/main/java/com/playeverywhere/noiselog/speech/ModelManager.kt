package com.playeverywhere.noiselog.speech

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File

object ModelManager {
    private const val PREFS = "speech_model"
    private const val OMNI_MODEL_ID = "model_download_id"
    private const val OMNI_TOKENS_ID = "tokens_download_id"
    private const val WHISPER_ENCODER_ID = "whisper_encoder_download_id"
    private const val WHISPER_DECODER_ID = "whisper_decoder_download_id"
    private const val WHISPER_TOKENS_ID = "whisper_tokens_download_id"

    private const val OMNI_MODEL_MIN_BYTES = 340_000_000L
    private const val OMNI_TOKENS_MIN_BYTES = 50_000L
    private const val WHISPER_ENCODER_MIN_BYTES = 12_000_000L
    private const val WHISPER_DECODER_MIN_BYTES = 85_000_000L
    private const val WHISPER_TOKENS_MIN_BYTES = 500_000L

    private const val OMNI_REPOSITORY =
        "https://huggingface.co/csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12/resolve/main"
    private const val WHISPER_REPOSITORY =
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main"

    data class Status(
        val ready: Boolean,
        val downloading: Boolean,
        val progress: Int,
        val message: String
    )

    private fun modelsRoot(context: Context): File = File(
        requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)),
        "models"
    )

    private fun omnilingualDirectory(context: Context) = File(modelsRoot(context), "omnilingual")
    private fun whisperDirectory(context: Context) = File(modelsRoot(context), "whisper-tiny")

    fun omnilingualModelFile(context: Context) = File(omnilingualDirectory(context), "model.int8.onnx")
    fun omnilingualTokensFile(context: Context) = File(omnilingualDirectory(context), "tokens.txt")
    fun whisperEncoderFile(context: Context) = File(whisperDirectory(context), "tiny-encoder.int8.onnx")
    fun whisperDecoderFile(context: Context) = File(whisperDirectory(context), "tiny-decoder.int8.onnx")
    fun whisperTokensFile(context: Context) = File(whisperDirectory(context), "tiny-tokens.txt")

    fun isReady(context: Context, language: String = RecognitionLanguage.AUTO): Boolean =
        if (language.isBlank()) {
            omnilingualModelFile(context).length() >= OMNI_MODEL_MIN_BYTES &&
                omnilingualTokensFile(context).length() >= OMNI_TOKENS_MIN_BYTES
        } else {
            whisperEncoderFile(context).length() >= WHISPER_ENCODER_MIN_BYTES &&
                whisperDecoderFile(context).length() >= WHISPER_DECODER_MIN_BYTES &&
                whisperTokensFile(context).length() >= WHISPER_TOKENS_MIN_BYTES
        }

    fun enqueue(context: Context, language: String = RecognitionLanguage.AUTO): Boolean =
        if (language.isBlank()) enqueueOmnilingual(context) else enqueueWhisper(context)

    private fun enqueueOmnilingual(context: Context): Boolean {
        if (isReady(context, RecognitionLanguage.AUTO)) return true
        omnilingualDirectory(context).mkdirs()
        val manager = context.getSystemService(DownloadManager::class.java) ?: return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        deleteIfIncomplete(omnilingualModelFile(context), OMNI_MODEL_MIN_BYTES)
        deleteIfIncomplete(omnilingualTokensFile(context), OMNI_TOKENS_MIN_BYTES)
        return try {
            val modelId = manager.enqueue(
                request(
                    context,
                    "$OMNI_REPOSITORY/model.int8.onnx?download=true",
                    "Шумограф: универсальная модель",
                    "Автоматическое распознавание 1600+ языков",
                    "models/omnilingual/model.int8.onnx"
                )
            )
            val tokensId = manager.enqueue(
                request(
                    context,
                    "$OMNI_REPOSITORY/tokens.txt?download=true",
                    "Шумограф: словарь универсальной модели",
                    "Подготовка офлайн-распознавания",
                    "models/omnilingual/tokens.txt"
                )
            )
            prefs.edit().putLong(OMNI_MODEL_ID, modelId).putLong(OMNI_TOKENS_ID, tokensId).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun enqueueWhisper(context: Context): Boolean {
        if (isReady(context, "ru")) return true
        whisperDirectory(context).mkdirs()
        val manager = context.getSystemService(DownloadManager::class.java) ?: return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        deleteIfIncomplete(whisperEncoderFile(context), WHISPER_ENCODER_MIN_BYTES)
        deleteIfIncomplete(whisperDecoderFile(context), WHISPER_DECODER_MIN_BYTES)
        deleteIfIncomplete(whisperTokensFile(context), WHISPER_TOKENS_MIN_BYTES)
        return try {
            val encoderId = manager.enqueue(
                request(
                    context,
                    "$WHISPER_REPOSITORY/tiny-encoder.int8.onnx?download=true",
                    "Шумограф: точный язык (1/3)",
                    "Компактная офлайн-модель",
                    "models/whisper-tiny/tiny-encoder.int8.onnx"
                )
            )
            val decoderId = manager.enqueue(
                request(
                    context,
                    "$WHISPER_REPOSITORY/tiny-decoder.int8.onnx?download=true",
                    "Шумограф: точный язык (2/3)",
                    "Компактная офлайн-модель",
                    "models/whisper-tiny/tiny-decoder.int8.onnx"
                )
            )
            val tokensId = manager.enqueue(
                request(
                    context,
                    "$WHISPER_REPOSITORY/tiny-tokens.txt?download=true",
                    "Шумограф: точный язык (3/3)",
                    "Словарь компактной модели",
                    "models/whisper-tiny/tiny-tokens.txt"
                )
            )
            prefs.edit()
                .putLong(WHISPER_ENCODER_ID, encoderId)
                .putLong(WHISPER_DECODER_ID, decoderId)
                .putLong(WHISPER_TOKENS_ID, tokensId)
                .apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun request(
        context: Context,
        url: String,
        title: String,
        description: String,
        destination: String
    ) = DownloadManager.Request(Uri.parse(url))
        .setTitle(title)
        .setDescription(description)
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(false)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalFilesDir(
            context,
            Environment.DIRECTORY_DOWNLOADS,
            destination
        )

    fun status(context: Context, language: String = RecognitionLanguage.AUTO): Status {
        if (isReady(context, language)) {
            return Status(
                true,
                false,
                100,
                if (language.isBlank()) "Универсальная офлайн-модель готова" else "Модель выбранного языка готова"
            )
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = if (language.isBlank()) {
            longArrayOf(prefs.getLong(OMNI_MODEL_ID, -1), prefs.getLong(OMNI_TOKENS_ID, -1))
        } else {
            longArrayOf(
                prefs.getLong(WHISPER_ENCODER_ID, -1),
                prefs.getLong(WHISPER_DECODER_ID, -1),
                prefs.getLong(WHISPER_TOKENS_ID, -1)
            )
        }.filter { it > 0 }
        val size = if (language.isBlank()) "365 МБ" else "105 МБ"
        if (ids.isEmpty()) return Status(false, false, 0, "Офлайн-модель не загружена · $size")

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
                active = active || itemStatus == DownloadManager.STATUS_RUNNING ||
                    itemStatus == DownloadManager.STATUS_PENDING || itemStatus == DownloadManager.STATUS_PAUSED
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

    fun delete(context: Context, language: String = RecognitionLanguage.AUTO): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (language.isBlank()) {
            val modelDeleted = deleteFile(omnilingualModelFile(context))
            val tokensDeleted = deleteFile(omnilingualTokensFile(context))
            prefs.edit().remove(OMNI_MODEL_ID).remove(OMNI_TOKENS_ID).apply()
            modelDeleted && tokensDeleted
        } else {
            val encoderDeleted = deleteFile(whisperEncoderFile(context))
            val decoderDeleted = deleteFile(whisperDecoderFile(context))
            val tokensDeleted = deleteFile(whisperTokensFile(context))
            prefs.edit()
                .remove(WHISPER_ENCODER_ID)
                .remove(WHISPER_DECODER_ID)
                .remove(WHISPER_TOKENS_ID)
                .apply()
            encoderDeleted && decoderDeleted && tokensDeleted
        }
    }

    private fun deleteIfIncomplete(file: File, minimumBytes: Long) {
        if (file.exists() && file.length() < minimumBytes) file.delete()
    }

    private fun deleteFile(file: File): Boolean = !file.exists() || file.delete()
}
