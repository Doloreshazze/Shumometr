package com.playeverywhere.noiselog

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.playeverywhere.noiselog.data.MeasurementDatabase
import com.playeverywhere.noiselog.data.PeriodSummary
import com.playeverywhere.noiselog.data.SessionSummary
import com.playeverywhere.noiselog.data.TranscriptEntry
import com.playeverywhere.noiselog.service.MeasurementService
import com.playeverywhere.noiselog.speech.ModelManager
import com.playeverywhere.noiselog.speech.RecognitionLanguage
import com.playeverywhere.noiselog.ui.HistoryChartView
import com.playeverywhere.noiselog.ui.MeterView
import com.playeverywhere.noiselog.ui.SpectrumView
import com.playeverywhere.noiselog.ui.WaveformView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class MainActivity : Activity() {
    companion object {
        private const val REQUEST_PERMISSIONS = 100
        private const val REQUEST_EXPORT = 101
        private const val PRIVACY_POLICY_URL = "https://doloreshazze.github.io/Shumometr/"
        private const val INK = 0xFF0A0F1E.toInt()
        private const val SURFACE = 0xFF121A2D.toInt()
        private const val SURFACE_2 = 0xFF182238.toInt()
        private const val CYAN = 0xFF42E8D4.toInt()
        private const val TEXT = 0xFFF4F7FF.toInt()
        private const val MUTED = 0xFFAEBAD4.toInt()
        private const val RED = 0xFFFF6577.toInt()

        @Volatile
        var isUiVisible = false
            private set
    }

    private lateinit var database: MeasurementDatabase
    private lateinit var content: FrameLayout
    private lateinit var tabButtons: List<Button>
    private val handler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val modelStatusRequestPending = AtomicBoolean(false)
    private var currentTab = 0
    private var pageGeneration = 0
    private var running = false
    private var lastButtonRunning: Boolean? = null
    private var pendingStart = false
    private var receiverRegistered = false
    @Volatile private var destroyed = false

    private var meterView: MeterView? = null
    private var waveformView: WaveformView? = null
    private var spectrumView: SpectrumView? = null
    private var historyView: HistoryChartView? = null
    private var startButton: Button? = null
    private var rawDbText: TextView? = null
    private var frequencyText: TextView? = null
    private var transcriptText: TextView? = null
    private var modelStatusText: TextView? = null
    private var modelProgress: ProgressBar? = null
    private var modelButton: Button? = null
    private var transcriptionSwitch: Switch? = null
    private var languageSpinner: Spinner? = null
    private var latestDb = 0.0

    private data class JournalSnapshot(
        val summaries: List<Pair<String, PeriodSummary>>,
        val sessions: List<SessionSummary>,
        val transcripts: List<TranscriptEntry>
    )

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MeasurementService.ACTION_ERROR -> {
                    Toast.makeText(this@MainActivity, intent.getStringExtra(MeasurementService.EXTRA_ERROR), Toast.LENGTH_LONG).show()
                    running = false
                    updateRunningUi()
                }
                MeasurementService.ACTION_UPDATE -> updateFromService(intent)
            }
        }
    }

    private val modelPoll = object : Runnable {
        override fun run() {
            val actualRunning = MeasurementService.isRunningNow
            if (running != actualRunning) {
                running = actualRunning
                updateRunningUi()
            }
            if (currentTab == 0) refreshModelStatus()
            handler.postDelayed(this, 2_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = INK
        window.navigationBarColor = INK
        database = MeasurementDatabase(this)
        running = MeasurementService.isRunningNow
        setContentView(buildRoot())
        selectTab(0)
    }

    override fun onStart() {
        super.onStart()
        isUiVisible = true
        running = MeasurementService.isRunningNow
        val filter = IntentFilter().apply {
            addAction(MeasurementService.ACTION_UPDATE)
            addAction(MeasurementService.ACTION_ERROR)
        }
        if (!receiverRegistered) {
            registerMeasurementReceiver(filter)
            receiverRegistered = true
        }
        handler.removeCallbacks(modelPoll)
        handler.post(modelPoll)
        updateRunningUi()
    }

    override fun onStop() {
        isUiVisible = false
        handler.removeCallbacks(modelPoll)
        if (receiverRegistered) {
            unregisterReceiver(receiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (!prefs.getBoolean("overlay_permission_pending", false)) return
        val granted = Settings.canDrawOverlays(this)
        prefs.edit()
            .putBoolean("overlay_permission_pending", false)
            .putBoolean("overlay_enabled", granted)
            .apply()
        if (::content.isInitialized && currentTab == 2) selectTab(2)
        Toast.makeText(
            this,
            if (granted) "Плавающий виджет включён" else "Разрешение на оверлей не выдано",
            Toast.LENGTH_SHORT
        ).show()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerMeasurementReceiver(filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        ioExecutor.shutdownNow()
        database.close()
        super.onDestroy()
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(INK)
            setPadding(dp(14), dp(12), dp(14), 0)
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("ШУМОГРАФ", 25f, TEXT, Typeface.BOLD))
                addView(label("шум · спектр · слова", 12f, MUTED))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label("● ЛОКАЛЬНО", 11f, CYAN, Typeface.BOLD).apply {
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = rounded(0x2242E8D4, 14f, CYAN, 1)
            })
        })

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(15), 0, dp(9))
        }
        tabButtons = listOf("Сейчас", "Журнал", "Настройки").mapIndexed { index, title ->
            Button(this).apply {
                text = title
                textSize = 13f
                isAllCaps = false
                setTextColor(MUTED)
                setPadding(dp(4), 0, dp(4), 0)
                minimumHeight = 0
                minHeight = 0
                background = rounded(SURFACE, 12f)
                setOnClickListener { selectTab(index) }
                tabs.addView(this, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    if (index > 0) marginStart = dp(7)
                })
            }
        }
        root.addView(tabs)
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun selectTab(index: Int) {
        if (currentTab == index && content.childCount > 0) return
        currentTab = index
        pageGeneration++
        tabButtons.forEachIndexed { buttonIndex, button ->
            button.setTextColor(if (buttonIndex == index) INK else MUTED)
            button.background = rounded(if (buttonIndex == index) CYAN else SURFACE, 12f)
        }
        content.removeAllViews()
        when (index) {
            0 -> showLivePage()
            1 -> showJournalPage()
            else -> showSettingsPage()
        }
    }

    private fun showLivePage() {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(22))
        }
        scroll.addView(column)

        val meterCard = card()
        meterView = MeterView(this).apply { setLevel(latestDb, running) }
        meterCard.addView(meterView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250)))
        startButton = primaryButton(if (running) "Остановить измерение" else "Начать круглосуточное измерение").apply {
            setOnClickListener { toggleMeasurement() }
        }
        lastButtonRunning = null
        meterCard.addView(startButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
            setMargins(dp(12), 0, dp(12), dp(12))
        })
        column.addView(meterCard, fullWrap(bottom = 10))

        val quickStats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rawDbText = addMiniStat(quickStats, "ЛИНЕЙНЫЙ", "— дБ")
        frequencyText = addMiniStat(quickStats, "ПИК СПЕКТРА", "— Гц")
        column.addView(quickStats, fullWrap(bottom = 10))

        val waveformCard = titledCard("СИГНАЛ ВО ВРЕМЕНИ", "последние 85 мс")
        waveformView = WaveformView(this)
        waveformCard.addView(waveformView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(112)).apply {
            setMargins(dp(10), 0, dp(10), dp(8))
        })
        column.addView(waveformCard, fullWrap(bottom = 10))

        val spectrumCard = titledCard("ЧАСТОТНЫЙ СПЕКТР", "20 Гц — 20 кГц")
        spectrumView = SpectrumView(this)
        spectrumCard.addView(spectrumView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)).apply {
            setMargins(dp(10), 0, dp(10), dp(8))
        })
        column.addView(spectrumCard, fullWrap(bottom = 10))

        val historyCard = titledCard("УРОВЕНЬ ЗА 3 МИНУТЫ", "одна точка в секунду")
        historyView = HistoryChartView(this)
        historyCard.addView(historyView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(145)).apply {
            setMargins(dp(8), 0, dp(8), dp(8))
        })
        column.addView(historyCard, fullWrap(bottom = 10))

        column.addView(buildTranscriptionCard(), fullWrap(bottom = 10))
        column.addView(label(
            "Данные уровня сохраняются раз в минуту. Исходный звук не записывается. Значение дБ(A) приблизительное до калибровки телефона.",
            12f,
            MUTED
        ).apply { setPadding(dp(8), dp(2), dp(8), dp(8)) })
        content.addView(scroll)
        updateRunningUi()
        refreshModelStatus()
    }

    private fun buildTranscriptionCard(): LinearLayout {
        val settings = getSharedPreferences("settings", MODE_PRIVATE)
        val box = titledCard("РАСШИФРОВКА БЕЗ ЦЕНЗУРЫ", "1600+ языков · офлайн")
        transcriptionSwitch = Switch(this).apply {
            text = "Сохранять все распознанные слова"
            textSize = 15f
            setTextColor(TEXT)
            isChecked = settings.getBoolean("transcription_enabled", false)
            setPadding(dp(12), dp(3), dp(12), dp(6))
            setOnCheckedChangeListener { _, enabled ->
                if (enabled && !ModelManager.isReady(this@MainActivity, selectedRecognitionLanguage())) {
                    isChecked = false
                    showModelRequiredDialog()
                } else if (enabled && !settings.getBoolean("transcription_consent", false)) {
                    isChecked = false
                    showTranscriptionDisclosure()
                } else {
                    settings.edit().putBoolean("transcription_enabled", enabled).apply()
                }
            }
        }
        box.addView(transcriptionSwitch)
        box.addView(label("Язык распознавания", 12f, MUTED, Typeface.BOLD).apply {
            setPadding(dp(12), dp(4), dp(12), 0)
        })
        val languageOptions = RecognitionLanguage.options()
        val storedLanguage = selectedRecognitionLanguage()
        languageSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                languageOptions.map { it.label }
            )
            setSelection(languageOptions.indexOfFirst { it.code == storedLanguage }.coerceAtLeast(0))
            setPadding(dp(8), 0, dp(8), dp(6))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val language = languageOptions[position].code
                    if (language == selectedRecognitionLanguage()) return
                    settings.edit().putString(RecognitionLanguage.PREF_KEY, language).apply()
                    if (settings.getBoolean("transcription_enabled", false) &&
                        !ModelManager.isReady(this@MainActivity, language)
                    ) {
                        settings.edit().putBoolean("transcription_enabled", false).apply()
                        transcriptionSwitch?.isChecked = false
                        showModelRequiredDialog(language)
                    }
                    refreshModelStatus()
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        }
        box.addView(languageSpinner)
        modelStatusText = label("", 12f, MUTED).apply { setPadding(dp(12), dp(2), dp(12), dp(3)) }
        box.addView(modelStatusText)
        modelProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        box.addView(modelProgress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)).apply {
            setMargins(dp(12), dp(2), dp(12), dp(7))
        })
        modelButton = secondaryButton("Загрузить модель").apply {
            setOnClickListener {
                val language = selectedRecognitionLanguage()
                val status = ModelManager.status(this@MainActivity, language)
                when {
                    status.ready -> showDeleteModelDialog(language)
                    status.downloading -> Toast.makeText(this@MainActivity, "Загрузка уже идёт", Toast.LENGTH_SHORT).show()
                    ModelManager.enqueue(this@MainActivity, language) -> refreshModelStatus()
                    else -> Toast.makeText(this@MainActivity, "Не удалось начать загрузку", Toast.LENGTH_LONG).show()
                }
            }
        }
        box.addView(modelButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
            setMargins(dp(12), 0, dp(12), dp(8))
        })
        transcriptText = label("Последние слова появятся здесь…", 14f, MUTED).apply {
            setPadding(dp(12), dp(9), dp(12), dp(12))
            background = rounded(INK, 10f)
        }
        box.addView(transcriptText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(12), 0, dp(12), dp(12))
        })
        return box
    }

    private fun showJournalPage() {
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
        }
        scroll.addView(column)
        column.addView(label("Загрузка журнала…", 16f, MUTED).apply { setPadding(dp(12), dp(24), dp(12), dp(24)) })
        content.addView(scroll)
        val generation = pageGeneration
        ioExecutor.execute {
            val result = runCatching {
                val queryDatabase = MeasurementDatabase(applicationContext)
                try {
                    val now = System.currentTimeMillis()
                    JournalSnapshot(
                        summaries = listOf(
                            "24 ЧАСА" to queryDatabase.periodSummary(now - 86_400_000L),
                            "7 ДНЕЙ" to queryDatabase.periodSummary(now - 7 * 86_400_000L),
                            "30 ДНЕЙ" to queryDatabase.periodSummary(now - 30L * 86_400_000L)
                        ),
                        sessions = queryDatabase.recentSessions(30),
                        transcripts = queryDatabase.recentTranscripts(30)
                    )
                } finally {
                    queryDatabase.close()
                }
            }
            handler.post {
                if (destroyed || currentTab != 1 || generation != pageGeneration) return@post
                column.removeAllViews()
                result.fold(
                    onSuccess = { populateJournal(column, it) },
                    onFailure = { column.addView(emptyState("Не удалось загрузить журнал: ${it.message.orEmpty()}")) }
                )
            }
        }
    }

    private fun populateJournal(column: LinearLayout, snapshot: JournalSnapshot) {
        column.addView(label("Статистика", 24f, TEXT, Typeface.BOLD).apply { setPadding(dp(2), dp(7), 0, dp(10)) })
        snapshot.summaries.forEach { (title, value) -> column.addView(periodCard(title, value), fullWrap(bottom = 8)) }
        column.addView(secondaryButton("Экспортировать весь журнал в CSV").apply {
            setOnClickListener { createExportDocument() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(16) })

        column.addView(sectionTitle("СЕАНСЫ ИЗМЕРЕНИЯ"))
        if (snapshot.sessions.isEmpty()) {
            column.addView(emptyState("Пока нет измерений. Запустите шумомер — первая запись появится через минуту."))
        } else {
            snapshot.sessions.forEach { column.addView(sessionCard(it), fullWrap(bottom = 8)) }
        }

        column.addView(sectionTitle("ПОСЛЕДНИЕ РАСПОЗНАННЫЕ СЛОВА").apply { setPadding(dp(2), dp(18), 0, dp(8)) })
        if (snapshot.transcripts.isEmpty()) {
            column.addView(emptyState("Расшифровок ещё нет. Установите языковую модель и включите функцию на вкладке «Сейчас»."))
        } else {
            val formatter = SimpleDateFormat("dd.MM · HH:mm:ss", Locale.getDefault())
            snapshot.transcripts.forEach { entry ->
                column.addView(card().apply {
                    addView(label(formatter.format(Date(entry.timestamp)), 11f, CYAN, Typeface.BOLD).apply {
                        setPadding(dp(12), dp(11), dp(12), dp(2))
                    })
                    addView(label(entry.text, 15f, TEXT).apply { setPadding(dp(12), dp(2), dp(12), dp(12)) })
                }, fullWrap(bottom = 7))
            }
        }
    }

    private fun showSettingsPage() {
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
        }
        scroll.addView(column)
        column.addView(label("Настройки", 24f, TEXT, Typeface.BOLD).apply { setPadding(dp(2), dp(7), 0, dp(10)) })

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val calibrationCard = titledCard("КАЛИБРОВКА", "поправка конкретного микрофона")
        val currentCalibration = prefs.getFloat("calibration_db", 0f)
        val calibrationValue = label("%+.1f дБ".format(currentCalibration), 22f, CYAN, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        calibrationCard.addView(calibrationValue)
        calibrationCard.addView(SeekBar(this).apply {
            max = 400
            progress = ((currentCalibration + 20f) * 10).roundToInt().coerceIn(0, 400)
            setPadding(dp(12), 0, dp(12), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = progress / 10f - 20f
                    calibrationValue.text = getString(R.string.signed_db_value, value)
                    if (fromUser) prefs.edit().putFloat("calibration_db", value).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
        calibrationCard.addView(label(
            "Положите рядом эталонный шумомер и двигайте поправку до совпадения показаний. Без калибровки телефон показывает полезную динамику, но не лабораторную абсолютную величину.",
            12f,
            MUTED
        ).apply { setPadding(dp(12), 0, dp(12), dp(12)) })
        column.addView(calibrationCard, fullWrap(bottom = 10))

        val storageCard = titledCard("ХРАНЕНИЕ ЖУРНАЛА", "сырые аудиофайлы не создаются")
        val options = listOf("7 дней", "30 дней", "90 дней", "1 год", "Без ограничения")
        val values = intArrayOf(7, 30, 90, 365, 0)
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, options)
            val selected = values.indexOf(prefs.getInt("retention_days", 90)).takeIf { it >= 0 } ?: 2
            setSelection(selected)
            setPadding(dp(8), 0, dp(8), dp(8))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    prefs.edit().putInt("retention_days", values[position]).apply()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        }
        storageCard.addView(spinner)
        storageCard.addView(label("Одна минута занимает лишь несколько десятков байт. Текст хранится без цензуры и доступен только этому приложению.", 12f, MUTED).apply {
            setPadding(dp(12), 0, dp(12), dp(12))
        })
        column.addView(storageCard, fullWrap(bottom = 10))

        val overlayAllowed = Settings.canDrawOverlays(this)
        val overlayCard = titledCard("ПЛАВАЮЩИЙ ВИДЖЕТ", "показания поверх других приложений")
        overlayCard.addView(Switch(this).apply {
            text = "Показывать виджет во время измерения"
            textSize = 15f
            setTextColor(TEXT)
            isChecked = overlayAllowed && prefs.getBoolean("overlay_enabled", false)
            setPadding(dp(12), 0, dp(12), dp(6))
            setOnCheckedChangeListener { button, enabled ->
                when {
                    !enabled -> prefs.edit().putBoolean("overlay_enabled", false).apply()
                    Settings.canDrawOverlays(this@MainActivity) -> {
                        prefs.edit().putBoolean("overlay_enabled", true).apply()
                    }
                    else -> {
                        button.isChecked = false
                        requestOverlayPermission()
                    }
                }
            }
        })
        overlayCard.addView(label(
            "Виджет показывает живую осциллограмму и частотный спектр, а сверху — дБ(A) и главную частоту. Его можно перетаскивать; крестик скрывает виджет, не останавливая журнал.",
            12f,
            MUTED
        ).apply { setPadding(dp(12), 0, dp(12), dp(8)) })
        if (overlayAllowed) {
            overlayCard.addView(label("● Разрешение выдано", 12f, CYAN, Typeface.BOLD).apply {
                setPadding(dp(12), 0, dp(12), dp(12))
            })
        } else {
            overlayCard.addView(secondaryButton("Разрешить показ поверх приложений").apply {
                setOnClickListener { requestOverlayPermission() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
                setMargins(dp(12), 0, dp(12), dp(12))
            })
        }
        column.addView(overlayCard, fullWrap(bottom = 10))

        val reliabilityCard = titledCard("КРУГЛОСУТОЧНАЯ РАБОТА", "Android всегда показывает уведомление")
        reliabilityCard.addView(label(
            "После запуска экран можно погасить. Частичная блокировка сна поддерживает измерение, но некоторые прошивки Samsung всё равно требуют исключить приложение из экономии батареи. После перезагрузки телефона измерение нужно запустить вручную.",
            13f,
            MUTED
        ).apply { setPadding(dp(12), 0, dp(12), dp(8)) })
        reliabilityCard.addView(secondaryButton("Открыть настройки батареи").apply {
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
            setMargins(dp(12), 0, dp(12), dp(12))
        })
        column.addView(reliabilityCard, fullWrap(bottom = 10))

        val privacyCard = titledCard("КОНФИДЕНЦИАЛЬНОСТЬ", "вся обработка — на телефоне")
        privacyCard.addView(label(
            "• звук не отправляется на сервер\n• непрерывная аудиозапись не сохраняется\n• для расшифровки в памяти остаются только короткие фразы\n• фильтра запрещённых слов нет\n• CSV создаётся только по вашей команде",
            13f,
            TEXT
        ).apply { setPadding(dp(12), 0, dp(12), dp(12)) })
        privacyCard.addView(secondaryButton("Открыть политику конфиденциальности").apply {
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)))
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
            setMargins(dp(12), 0, dp(12), dp(12))
        })
        column.addView(privacyCard, fullWrap(bottom = 10))
        content.addView(scroll)
    }

    private fun requestOverlayPermission() {
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putBoolean("overlay_permission_pending", true)
            .apply()
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun updateFromService(intent: Intent) {
        running = intent.getBooleanExtra(MeasurementService.EXTRA_RUNNING, running)
        if (intent.hasExtra(MeasurementService.EXTRA_DB)) {
            latestDb = intent.getDoubleExtra(MeasurementService.EXTRA_DB, latestDb)
            val raw = intent.getDoubleExtra(MeasurementService.EXTRA_DB_UNWEIGHTED, 0.0)
            val frequency = intent.getDoubleExtra(MeasurementService.EXTRA_DOMINANT_HZ, 0.0)
            rawDbText?.text = getString(R.string.db_value, raw)
            frequencyText?.text = if (frequency >= 1000) "%.2f кГц".format(frequency / 1000) else "%.0f Гц".format(frequency)
            intent.getFloatArrayExtra(MeasurementService.EXTRA_WAVEFORM)?.let { waveformView?.setWaveform(it) }
            val spectrum = intent.getFloatArrayExtra(MeasurementService.EXTRA_SPECTRUM)
            val frequencies = intent.getFloatArrayExtra(MeasurementService.EXTRA_FREQUENCIES)
            if (spectrum != null && frequencies != null) spectrumView?.setSpectrum(spectrum, frequencies)
            intent.getFloatArrayExtra(MeasurementService.EXTRA_HISTORY)?.let { historyView?.setHistory(it) }
        }
        intent.getStringExtra(MeasurementService.EXTRA_TRANSCRIPT)?.takeIf { it.isNotBlank() }?.let {
            transcriptText?.text = it
            transcriptText?.setTextColor(TEXT)
        }
        updateRunningUi()
    }

    private fun toggleMeasurement() {
        if (running) {
            running = false
            updateRunningUi()
            startService(Intent(this, MeasurementService::class.java).setAction(MeasurementService.ACTION_STOP))
            return
        }
        val permissions = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permissions += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions += Manifest.permission.POST_NOTIFICATIONS
        if (permissions.isNotEmpty()) {
            pendingStart = true
            requestPermissions(permissions.toTypedArray(), REQUEST_PERMISSIONS)
        } else startMeasurementService()
    }

    private fun startMeasurementService() {
        val intent = Intent(this, MeasurementService::class.java).setAction(MeasurementService.ACTION_START)
        startForegroundService(intent)
        running = true
        updateRunningUi()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS || !pendingStart) return
        pendingStart = false
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startMeasurementService()
        else Toast.makeText(this, "Без микрофона шумомер не может работать", Toast.LENGTH_LONG).show()
    }

    private fun updateRunningUi() {
        meterView?.setLevel(latestDb, running)
        if (lastButtonRunning == running) return
        lastButtonRunning = running
        startButton?.apply {
            text = if (running) "Остановить измерение" else "Начать круглосуточное измерение"
            setTextColor(if (running) Color.WHITE else INK)
            background = rounded(if (running) RED else CYAN, 14f)
        }
    }

    private fun refreshModelStatus() {
        if (!modelStatusRequestPending.compareAndSet(false, true)) return
        val language = selectedRecognitionLanguage()
        ioExecutor.execute {
            val status = runCatching { ModelManager.status(applicationContext, language) }.getOrNull()
            handler.post {
                modelStatusRequestPending.set(false)
                if (destroyed || currentTab != 0 || status == null) return@post
                if (language != selectedRecognitionLanguage()) {
                    refreshModelStatus()
                    return@post
                }
                modelStatusText?.text = status.message
                modelStatusText?.setTextColor(if (status.ready) CYAN else MUTED)
                modelProgress?.visibility = if (status.downloading) View.VISIBLE else View.GONE
                modelProgress?.progress = status.progress
                modelButton?.text = when {
                    status.ready -> "Модель установлена · управление"
                    status.downloading -> "Загрузка… ${status.progress}%"
                    language.isBlank() -> "Загрузить универсальную модель · 365 МБ"
                    else -> "Загрузить модель точного языка · 105 МБ"
                }
                transcriptionSwitch?.isEnabled = status.ready
            }
        }
    }

    private fun showModelRequiredDialog(language: String = selectedRecognitionLanguage()) {
        val targeted = language.isNotBlank()
        AlertDialog.Builder(this)
            .setTitle("Нужна офлайн-модель")
            .setMessage(
                if (targeted) {
                    "Для фиксации выбранного языка нужна компактная офлайн-модель Whisper. " +
                        "Она не цензурирует слова и не отправляет речь в облако. Размер загрузки — около 105 МБ."
                } else {
                    "Универсальная модель понимает более 1600 языков, не цензурирует слова и не отправляет речь в облако. " +
                        "Размер загрузки — около 365 МБ."
                }
            )
            .setNegativeButton("Позже", null)
            .setPositiveButton("Загрузить") { _, _ ->
                if (!ModelManager.enqueue(this, language)) Toast.makeText(this, "Не удалось начать загрузку", Toast.LENGTH_LONG).show()
                refreshModelStatus()
            }
            .show()
    }

    private fun showTranscriptionDisclosure() {
        AlertDialog.Builder(this)
            .setTitle("Непрерывная локальная расшифровка")
            .setMessage(
                "Пока измерение запущено, микрофон анализирует речь даже при погашенном экране. " +
                    "Распознанный текст сохраняется в журнале на этом телефоне; исходный звук не сохраняется " +
                    "и не отправляется в облако. Убедитесь, что использование функции законно и люди рядом " +
                    "проинформированы, если этого требуют местные правила."
            )
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Понимаю, включить") { _, _ ->
                getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putBoolean("transcription_consent", true)
                    .putBoolean("transcription_enabled", true)
                    .apply()
                transcriptionSwitch?.isChecked = true
            }
            .show()
    }

    private fun showDeleteModelDialog(language: String) {
        val targeted = language.isNotBlank()
        AlertDialog.Builder(this)
            .setTitle("Офлайн-модель установлена")
            .setMessage(
                "Она занимает около ${if (targeted) "105" else "365"} МБ. Удалить её? " +
                    "Шумомер и журнал уровней продолжат работать, но расшифровка в этом режиме отключится."
            )
            .setNegativeButton("Оставить", null)
            .setPositiveButton("Удалить") { _, _ ->
                getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("transcription_enabled", false).apply()
                ModelManager.delete(this, language)
                refreshModelStatus()
            }
            .show()
    }

    private fun selectedRecognitionLanguage(): String {
        val stored = getSharedPreferences("settings", MODE_PRIVATE)
            .getString(RecognitionLanguage.PREF_KEY, RecognitionLanguage.AUTO)
            .orEmpty()
        return stored.takeIf(RecognitionLanguage::isSupported) ?: RecognitionLanguage.AUTO
    }

    private fun createExportDocument() {
        val name = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/csv"
                putExtra(Intent.EXTRA_TITLE, "NoiseLog_$name.csv")
            },
            REQUEST_EXPORT
        )
    }

    @Deprecated("Legacy result API keeps the project dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        Thread {
            try {
                contentResolver.openOutputStream(uri)?.use { database.exportCsv(it) }
                runOnUiThread { Toast.makeText(this, "Журнал экспортирован", Toast.LENGTH_SHORT).show() }
            } catch (error: Exception) {
                runOnUiThread { Toast.makeText(this, "Ошибка экспорта: ${error.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun periodCard(title: String, value: PeriodSummary): View = card().apply {
        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(11), dp(12), dp(11))
        }
        row.addView(label(title, 12f, MUTED, Typeface.BOLD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label(if (value.minutes > 0) "Leq %.1f дБ".format(value.leqDb) else "нет данных", 16f, CYAN, Typeface.BOLD))
        addView(row)
        if (value.minutes > 0) addView(label(
            "мин ${value.minDb.roundToInt()} · макс ${value.maxDb.roundToInt()} дБ · ${value.minutes} мин · слов: ${value.transcriptCount}",
            12f,
            MUTED
        ).apply { setPadding(dp(12), 0, dp(12), dp(11)) })
    }

    private fun sessionCard(session: SessionSummary): View = card().apply {
        val date = SimpleDateFormat("dd.MM.yyyy · HH:mm", Locale.getDefault()).format(Date(session.startedAt))
        val duration = formatDuration((session.endedAt ?: System.currentTimeMillis()) - session.startedAt)
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(11), dp(12), dp(2))
            addView(label(date, 13f, TEXT, Typeface.BOLD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label(if (session.endedAt == null) "● идёт" else duration, 12f, if (session.endedAt == null) CYAN else MUTED))
        })
        addView(label(
            "Leq %.1f · %.0f—%.0f дБ(A) · пик %.0f Гц · фраз: %d".format(
                session.leqDb,
                session.minDb,
                session.maxDb,
                session.dominantHz,
                session.transcriptCount
            ),
            12f,
            MUTED
        ).apply { setPadding(dp(12), dp(3), dp(12), dp(11)) })
    }

    private fun addMiniStat(parent: LinearLayout, title: String, initial: String): TextView {
        val value = label(initial, 18f, TEXT, Typeface.BOLD)
        val box = card().apply {
            addView(label(title, 10f, MUTED, Typeface.BOLD).apply { setPadding(dp(11), dp(10), dp(11), dp(1)) })
            addView(value.apply { setPadding(dp(11), 0, dp(11), dp(10)) })
        }
        parent.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (parent.childCount > 0) marginStart = dp(8)
        })
        return value
    }

    private fun titledCard(title: String, subtitle: String): LinearLayout = card().apply {
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(11), dp(12), dp(7))
            addView(label(title, 11f, TEXT, Typeface.BOLD), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label(subtitle, 10f, MUTED))
        })
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(SURFACE, 16f, 0xFF26324A.toInt(), 1)
        clipToOutline = true
    }

    private fun emptyState(text: String) = label(text, 13f, MUTED).apply {
        setPadding(dp(14), dp(18), dp(14), dp(18))
        gravity = Gravity.CENTER
        background = rounded(SURFACE, 14f)
    }

    private fun sectionTitle(text: String) = label(text, 11f, MUTED, Typeface.BOLD).apply {
        setPadding(dp(2), dp(3), 0, dp(8))
    }

    private fun primaryButton(title: String) = Button(this).apply {
        text = title
        textSize = 14f
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(INK)
        background = rounded(CYAN, 14f)
    }

    private fun secondaryButton(title: String) = Button(this).apply {
        text = title
        textSize = 13f
        isAllCaps = false
        setTextColor(CYAN)
        background = rounded(SURFACE_2, 12f, CYAN, 1)
    }

    private fun label(textValue: String, size: Float, color: Int, style: Int = Typeface.NORMAL) = TextView(this).apply {
        text = textValue
        textSize = size
        setTextColor(color)
        setTypeface(Typeface.create("sans", style))
        setLineSpacing(0f, 1.08f)
    }

    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int = Color.TRANSPARENT, strokeDp: Int = 0) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp)
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    private fun fullWrap(bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(bottom) }

    private fun formatDuration(milliseconds: Long): String {
        val totalMinutes = (milliseconds / 60_000L).coerceAtLeast(0)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "$hours ч $minutes мин" else "$minutes мин"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun dp(value: Float) = value * resources.displayMetrics.density
}
