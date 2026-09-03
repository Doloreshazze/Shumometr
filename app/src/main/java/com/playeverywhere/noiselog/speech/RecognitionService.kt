package com.playeverywhere.noiselog.speech

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.playeverywhere.noiselog.service.MeasurementService

/**
 * Runs the large ONNX model in a private secondary process. A slow or wedged
 * native decoder must never stop microphone capture, graphs, or the stop button.
 */
class RecognitionService : Service() {
    companion object {
        private const val TAG = "ShumometrRecognition"
        const val ACTION_PREPARE = "com.playeverywhere.noiselog.RECOGNITION_PREPARE"
        const val ACTION_TRANSCRIBE = "com.playeverywhere.noiselog.RECOGNITION_TRANSCRIBE"
        const val ACTION_RESULT = "com.playeverywhere.noiselog.RECOGNITION_RESULT"
        const val EXTRA_SAMPLES = "samples"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_STARTED_AT = "started_at"
        const val EXTRA_LANGUAGE = "language"
        const val EXTRA_TARGET_LANGUAGE = "target_language"
    }

    private lateinit var transcriber: OfflineTranscriber
    @Volatile private var shuttingDown = false

    override fun onCreate() {
        super.onCreate()
        transcriber = OfflineTranscriber(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREPARE -> transcriber.prepare(
                intent.getStringExtra(EXTRA_TARGET_LANGUAGE).orEmpty()
            )
            ACTION_TRANSCRIBE -> {
                val samples = intent.getShortArrayExtra(EXTRA_SAMPLES) ?: return START_NOT_STICKY
                val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
                val startedAt = intent.getLongExtra(EXTRA_STARTED_AT, System.currentTimeMillis())
                val targetLanguage = intent.getStringExtra(EXTRA_TARGET_LANGUAGE).orEmpty()
                transcriber.submit(samples, targetLanguage) { result ->
                    if (shuttingDown) return@submit
                    sendBroadcast(
                        Intent(ACTION_RESULT)
                            .setPackage(packageName)
                            .putExtra(EXTRA_SESSION_ID, sessionId)
                            .putExtra(EXTRA_STARTED_AT, startedAt)
                            .putExtra(MeasurementService.EXTRA_TRANSCRIPT, result.text)
                            .putExtra(EXTRA_LANGUAGE, result.language)
                    )
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        shuttingDown = true
        transcriber.close()
        super.onDestroy()
        Log.i(TAG, "Recognition process stopped")
    }
}
