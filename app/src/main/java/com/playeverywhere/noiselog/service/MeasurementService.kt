package com.playeverywhere.noiselog.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.util.Log
import com.playeverywhere.noiselog.MainActivity
import com.playeverywhere.noiselog.R
import com.playeverywhere.noiselog.audio.AudioAnalyzer
import com.playeverywhere.noiselog.audio.FrameAnalysis
import com.playeverywhere.noiselog.audio.VoiceSegmenter
import com.playeverywhere.noiselog.data.LevelAccumulator
import com.playeverywhere.noiselog.data.MeasurementDatabase
import com.playeverywhere.noiselog.speech.ModelManager
import com.playeverywhere.noiselog.speech.RecognitionService
import com.playeverywhere.noiselog.speech.RecognitionLanguage
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MeasurementService : Service() {
    companion object {
        private const val TAG = "ShumometrService"
        const val ACTION_START = "com.playeverywhere.noiselog.START"
        const val ACTION_STOP = "com.playeverywhere.noiselog.STOP"
        const val ACTION_UPDATE = "com.playeverywhere.noiselog.UPDATE"
        const val ACTION_ERROR = "com.playeverywhere.noiselog.ERROR"

        const val EXTRA_DB = "db"
        const val EXTRA_DB_UNWEIGHTED = "db_unweighted"
        const val EXTRA_DOMINANT_HZ = "dominant_hz"
        const val EXTRA_WAVEFORM = "waveform"
        const val EXTRA_SPECTRUM = "spectrum"
        const val EXTRA_FREQUENCIES = "frequencies"
        const val EXTRA_HISTORY = "history"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_TRANSCRIPT = "transcript"
        const val EXTRA_ERROR = "error"

        private const val SAMPLE_RATE = 48_000
        private const val FRAME_SIZE = 4096
        private const val NOTIFICATION_ID = 4102
        private const val CHANNEL_ID = "continuous_measurement"

        @Volatile
        var isRunningNow = false
            private set
    }

    private val running = AtomicBoolean(false)
    private lateinit var database: MeasurementDatabase
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockAcquiredAt = 0L
    @Volatile private var sessionId = -1L
    @Volatile private var recognitionActive = false
    private val recognitionResults = Executors.newSingleThreadExecutor()
    private var lastTranscript = ""
    private lateinit var overlayWidget: OverlayWidgetController
    private var recognitionReceiverRegistered = false
    private var recognitionLanguage = RecognitionLanguage.AUTO

    private val recognitionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RecognitionService.ACTION_RESULT) return
            val resultSessionId = intent.getLongExtra(RecognitionService.EXTRA_SESSION_ID, -1L)
            val startedAt = intent.getLongExtra(RecognitionService.EXTRA_STARTED_AT, System.currentTimeMillis())
            val text = intent.getStringExtra(EXTRA_TRANSCRIPT).orEmpty().trim()
            val language = intent.getStringExtra(RecognitionService.EXTRA_LANGUAGE).orEmpty()
            if (text.isEmpty() || resultSessionId <= 0 || resultSessionId != sessionId) return
            recognitionResults.execute {
                val resultDatabase = MeasurementDatabase(applicationContext)
                try {
                    resultDatabase.appendTranscript(resultSessionId, startedAt, text, language)
                    if (resultSessionId == sessionId && running.get()) {
                        lastTranscript = text
                        sendState(null)
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "Unable to save transcript", error)
                } finally {
                    resultDatabase.close()
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        database = MeasurementDatabase(this)
        overlayWidget = OverlayWidgetController(this)
        createNotificationChannel()
        val filter = IntentFilter(RecognitionService.ACTION_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(recognitionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(recognitionReceiver, filter)
        }
        recognitionReceiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMeasurement()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running.get()) startMeasurement()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startMeasurement() {
        // A persisted flag can survive an Android service termination. Clear it
        // before initialization and publish true only after the microphone starts.
        persistRunningState(false)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            reportError("Нет разрешения на микрофон")
            stopSelf()
            return
        }
        try {
            startAsForeground(buildNotification(0.0))
        } catch (error: Exception) {
            reportError("Android не разрешил фоновое измерение: ${error.message.orEmpty()}")
            stopSelf()
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            reportError("Микрофон не поддерживает 48 кГц")
            stopSelf()
            return
        }

        val source = preferredAudioSource()
        val record = try {
            AudioRecord(
                source,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 2, FRAME_SIZE * 4)
            )
        } catch (error: Exception) {
            reportError("Не удалось открыть микрофон: ${error.message.orEmpty()}")
            stopSelf()
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            reportError("Микрофон занят другим приложением")
            stopSelf()
            return
        }

        try {
            record.startRecording()
        } catch (error: Exception) {
            record.release()
            reportError("Не удалось запустить микрофон: ${error.message.orEmpty()}")
            stopSelf()
            return
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            reportError("Микрофон не перешёл в режим записи")
            stopSelf()
            return
        }

        audioRecord = record
        sessionId = try {
            database.startSession()
        } catch (error: Exception) {
            record.stop()
            record.release()
            audioRecord = null
            reportError("Не удалось открыть журнал: ${error.message.orEmpty()}")
            stopSelf()
            return
        }
        acquireWakeLock()
        running.set(true)
        persistRunningState(true)
        Log.i(TAG, "Measurement started, session=$sessionId")
        overlayWidget.update(Double.NaN, 0.0, null, null, true)
        worker = Thread({ captureLoop(record) }, "NoiseLog-Audio").apply { start() }
        sendState(null)
    }

    private fun captureLoop(record: AudioRecord) {
        val analyzer = AudioAnalyzer(SAMPLE_RATE)
        val segmenter = VoiceSegmenter(SAMPLE_RATE, 16_000)
        val minute = LevelAccumulator()
        val history = ArrayDeque<Float>()
        val buffer = ShortArray(FRAME_SIZE)
        var lastUiUpdate = 0L
        var lastOverlayUpdate = 0L
        var lastHistoryUpdate = 0L
        var lastModelCheck = 0L
        var lastMinuteFlush = System.currentTimeMillis()
        var lastNotificationUpdate = 0L
        var modelReady = false
        var historySnapshot = FloatArray(0)
        var historyChanged = false
        var latest: FrameAnalysis? = null

        while (running.get()) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val count = try {
                record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            } catch (_: Exception) {
                AudioRecord.ERROR_INVALID_OPERATION
            }
            // Audio priority is useful only while waiting for microphone data.
            // FFT, database work and UI transport must not starve the main thread.
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            if (count <= 0) {
                if (count == AudioRecord.ERROR_DEAD_OBJECT) break
                continue
            }
            if (!running.get()) break

            val settings = getSharedPreferences("settings", MODE_PRIVATE)
            val calibration = settings.getFloat("calibration_db", 0f).toDouble()
            latest = analyzer.analyze(buffer, count, calibration)
            minute.add(latest.dbA, latest.dominantHz)
            val now = System.currentTimeMillis()
            keepWakeLockAlive(now)

            val transcriptionEnabled = settings.getBoolean("transcription_enabled", false)
            if (now - lastModelCheck >= 5_000L) {
                val selectedLanguage = settings.getString(
                    RecognitionLanguage.PREF_KEY,
                    RecognitionLanguage.AUTO
                ).orEmpty().takeIf(RecognitionLanguage::isSupported) ?: RecognitionLanguage.AUTO
                if (selectedLanguage != recognitionLanguage) {
                    recognitionLanguage = selectedLanguage
                    // The recognition process swaps models on its own single-thread
                    // executor. Reusing the service avoids loading two native models
                    // concurrently while the old process is still winding down.
                    recognitionActive = false
                }
                modelReady = transcriptionEnabled && ModelManager.isReady(this, recognitionLanguage)
                lastModelCheck = now
            }
            val shouldRecognize = transcriptionEnabled && modelReady
            if (shouldRecognize && !recognitionActive) prepareRecognition()
            if (!shouldRecognize && recognitionActive) stopRecognition()
            if (shouldRecognize) {
                val segment = segmenter.accept(buffer, count, latest.dbFs, now)
                if (segment != null) submitTranscription(segment.startedAt, segment.samples)
            }

            if (now - lastHistoryUpdate >= 1_000L) {
                history.addLast(latest.dbA.toFloat())
                while (history.size > 180) history.removeFirst()
                historySnapshot = history.toFloatArray()
                historyChanged = true
                lastHistoryUpdate = now
            }
            if (now - lastOverlayUpdate >= 250L) {
                overlayWidget.update(
                    latest.dbA,
                    latest.dominantHz,
                    latest.waveform,
                    latest.spectrum,
                    true
                )
                lastOverlayUpdate = now
            }
            if (now - lastUiUpdate >= 250L) {
                sendState(latest, if (historyChanged) historySnapshot else null)
                historyChanged = false
                lastUiUpdate = now
            }
            if (now - lastNotificationUpdate >= 5_000L) {
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    buildNotification(latest.dbA)
                )
                lastNotificationUpdate = now
            }
            if (now - lastMinuteFlush >= 60_000L) {
                minute.snapshotAndReset(now)?.let { database.appendMinute(sessionId, it) }
                val retention = settings.getInt("retention_days", 90)
                database.cleanup(retention)
                lastMinuteFlush = now
            }
        }

        val trailing = segmenter.flush()
        if (trailing != null) submitTranscription(trailing.startedAt, trailing.samples)
        minute.snapshotAndReset()?.let { database.appendMinute(sessionId, it) }
        latest?.let { sendState(it, history.toFloatArray()) }
        if (running.get()) {
            reportError("Поток микрофона неожиданно остановился")
            stopSelf()
        }
    }

    private fun submitTranscription(startedAt: Long, samples: ShortArray) {
        if (!running.get() || sessionId <= 0) return
        if (!recognitionActive) prepareRecognition()
        try {
            startService(
                Intent(this, RecognitionService::class.java)
                    .setAction(RecognitionService.ACTION_TRANSCRIBE)
                    .putExtra(RecognitionService.EXTRA_SESSION_ID, sessionId)
                    .putExtra(RecognitionService.EXTRA_STARTED_AT, startedAt)
                    .putExtra(RecognitionService.EXTRA_TARGET_LANGUAGE, recognitionLanguage)
                    .putExtra(RecognitionService.EXTRA_SAMPLES, samples)
            )
        } catch (error: Exception) {
            Log.e(TAG, "Unable to submit speech segment", error)
        }
    }

    private fun prepareRecognition() {
        try {
            startService(
                Intent(this, RecognitionService::class.java)
                    .setAction(RecognitionService.ACTION_PREPARE)
                    .putExtra(RecognitionService.EXTRA_TARGET_LANGUAGE, recognitionLanguage)
            )
            recognitionActive = true
        } catch (error: Exception) {
            recognitionActive = false
            Log.e(TAG, "Unable to start recognition process", error)
        }
    }

    private fun stopRecognition() {
        recognitionActive = false
        try {
            stopService(Intent(this, RecognitionService::class.java))
        } catch (error: Exception) {
            Log.w(TAG, "Unable to stop recognition process", error)
        }
    }

    @Synchronized
    private fun stopMeasurement() {
        overlayWidget.update(Double.NaN, 0.0, null, null, false)
        val wasRunning = running.getAndSet(false)
        persistRunningState(false)
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        if (Thread.currentThread() !== worker) {
            try {
                worker?.join(500L)
            } catch (_: InterruptedException) {
            }
        }
        audioRecord?.release()
        audioRecord = null
        worker = null
        if (sessionId > 0) database.finishSession(sessionId)
        sessionId = -1L
        stopRecognition()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        sendState(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (wasRunning) Log.i(TAG, "Measurement stopped")
    }

    override fun onDestroy() {
        stopMeasurement()
        if (recognitionReceiverRegistered) {
            unregisterReceiver(recognitionReceiver)
            recognitionReceiverRegistered = false
        }
        recognitionResults.shutdownNow()
        overlayWidget.release()
        database.close()
        super.onDestroy()
    }

    private fun preferredAudioSource(): Int {
        val audioManager = getSystemService(AudioManager::class.java)
        if (audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") {
            return MediaRecorder.AudioSource.UNPROCESSED
        }
        return MediaRecorder.AudioSource.MIC
    }

    private fun acquireWakeLock() {
        val manager = getSystemService(PowerManager::class.java)
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NoiseLog:measurement").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1_000L)
        }
        wakeLockAcquiredAt = System.currentTimeMillis()
    }

    private fun keepWakeLockAlive(now: Long) {
        if (now - wakeLockAcquiredAt < 10 * 60 * 60 * 1_000L) return
        val lock = wakeLock ?: return
        if (lock.isHeld) lock.release()
        lock.acquire(12 * 60 * 60 * 1_000L)
        wakeLockAcquiredAt = now
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(db: Double): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, MeasurementService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (db > 0) String.format(Locale.getDefault(), "%.1f дБ(A) · журнал ведётся", db)
        else "Подготовка микрофона…"
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Шумограф работает")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(R.drawable.ic_stop, "Остановить", stopIntent).build())
            .build()
    }

    private fun reportError(message: String) {
        Log.e(TAG, message)
        sendBroadcast(
            Intent(ACTION_ERROR)
                .setPackage(packageName)
                .putExtra(EXTRA_ERROR, message)
        )
    }

    private fun sendState(analysis: FrameAnalysis?, history: FloatArray? = null) {
        if (!MainActivity.isUiVisible) return
        val intent = Intent(ACTION_UPDATE)
            .setPackage(packageName)
            .putExtra(EXTRA_RUNNING, running.get())
            .putExtra(EXTRA_TRANSCRIPT, lastTranscript)
        if (analysis != null) {
            intent.putExtra(EXTRA_DB, analysis.dbA)
            intent.putExtra(EXTRA_DB_UNWEIGHTED, analysis.dbUnweighted)
            intent.putExtra(EXTRA_DOMINANT_HZ, analysis.dominantHz)
            intent.putExtra(EXTRA_WAVEFORM, analysis.waveform)
            intent.putExtra(EXTRA_SPECTRUM, analysis.spectrum)
            intent.putExtra(EXTRA_FREQUENCIES, analysis.spectrumFrequencies)
        }
        if (history != null) intent.putExtra(EXTRA_HISTORY, history)
        sendBroadcast(intent)
    }

    private fun persistRunningState(value: Boolean) {
        isRunningNow = value
        getSharedPreferences("service_state", MODE_PRIVATE)
            .edit()
            .putBoolean("running", value)
            .apply()
    }
}
