package com.playeverywhere.noiselog.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.playeverywhere.noiselog.MainActivity
import java.util.Locale
import kotlin.math.abs

/** Small, movable waveform and spectrum panel shown above other applications. */
class OverlayWidgetController(private val context: Context) {
    companion object {
        private const val PREFS = "settings"
        private const val PREF_ENABLED = "overlay_enabled"
        private const val PREF_X = "overlay_x"
        private const val PREF_Y = "overlay_y"
        private const val INK = 0xF2121A2D.toInt()
        private const val TEXT = 0xFFF4F7FF.toInt()
        private const val MUTED = 0xFFAEBAD4.toInt()
        private const val CYAN = 0xFF42E8D4.toInt()
        private const val YELLOW = 0xFFFFD166.toInt()
        private const val ORANGE = 0xFFFF9F43.toInt()
        private const val RED = 0xFFFF6577.toInt()
    }

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var levelText: TextView? = null
    private var frequencyText: TextView? = null
    private var statusDot: TextView? = null
    private var cardBackground: GradientDrawable? = null
    private var graphsView: OverlayGraphsView? = null
    private var currentAccent = CYAN

    @Volatile private var latestDb = Double.NaN
    @Volatile private var latestFrequency = 0.0
    @Volatile private var latestWaveform = FloatArray(0)
    @Volatile private var latestSpectrum = FloatArray(0)
    @Volatile private var measurementRunning = false

    private val renderRunnable = Runnable { render() }

    fun update(
        db: Double,
        frequency: Double,
        waveform: FloatArray?,
        spectrum: FloatArray?,
        running: Boolean
    ) {
        latestDb = db
        latestFrequency = frequency
        // FrameAnalysis owns fresh immutable arrays, so retaining references is
        // safe and avoids four allocations per overlay frame.
        if (waveform != null) latestWaveform = waveform
        if (spectrum != null) latestSpectrum = spectrum
        measurementRunning = running
        mainHandler.removeCallbacks(renderRunnable)
        mainHandler.post(renderRunnable)
    }

    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        if (Looper.myLooper() == Looper.getMainLooper()) hide() else mainHandler.post { hide() }
    }

    private fun render() {
        if (
            !measurementRunning ||
            MainActivity.isUiVisible ||
            !prefs.getBoolean(PREF_ENABLED, false) ||
            !Settings.canDrawOverlays(context)
        ) {
            hide()
            return
        }
        ensureShown()
        val db = latestDb
        levelText?.text = if (db.isFinite()) String.format(Locale.getDefault(), "%.1f дБ(A)", db) else "— дБ(A)"
        frequencyText?.text = when {
            latestFrequency <= 0.0 -> "пик — Гц"
            latestFrequency >= 1_000.0 -> String.format(Locale.getDefault(), "пик %.2f кГц", latestFrequency / 1_000.0)
            else -> String.format(Locale.getDefault(), "пик %.0f Гц", latestFrequency)
        }
        val accent = when {
            !db.isFinite() || db < 55.0 -> CYAN
            db < 70.0 -> YELLOW
            db < 85.0 -> ORANGE
            else -> RED
        }
        statusDot?.setTextColor(accent)
        if (accent != currentAccent) {
            currentAccent = accent
            cardBackground?.setStroke(dp(1), accent)
        }
        graphsView?.setData(latestWaveform, latestSpectrum, accent)
    }

    private fun ensureShown() {
        if (root != null) return
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(INK)
            setStroke(dp(1), CYAN)
        }
        cardBackground = background

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(6), dp(9), dp(8))
            this.background = background
            elevation = dp(12).toFloat()
            isClickable = true
            contentDescription = "Открыть Шумограф"
            setOnClickListener { openApp() }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = text("●", 11f, CYAN, Typeface.BOLD).also {
            header.addView(it, LinearLayout.LayoutParams(dp(15), dp(28)))
        }
        levelText = text("— дБ(A)", 16f, TEXT, Typeface.BOLD).also {
            header.addView(it, LinearLayout.LayoutParams(0, dp(28), 1f))
        }
        frequencyText = text("пик — Гц", 10f, MUTED).also {
            it.gravity = Gravity.CENTER
            header.addView(it, LinearLayout.LayoutParams(dp(69), dp(28)))
        }
        header.addView(text("×", 22f, MUTED).apply {
            gravity = Gravity.CENTER
            isClickable = true
            contentDescription = "Скрыть плавающий виджет"
            setOnClickListener {
                prefs.edit().putBoolean(PREF_ENABLED, false).apply()
                hide()
            }
        }, LinearLayout.LayoutParams(dp(30), dp(30)))
        card.addView(header)

        graphsView = OverlayGraphsView(context).also {
            card.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(116)))
        }

        val width = dp(228)
        val defaultX = (context.resources.displayMetrics.widthPixels - width - dp(14)).coerceAtLeast(0)
        val layout = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.contains(PREF_X)) prefs.getInt(PREF_X, defaultX) else defaultX
            y = prefs.getInt(PREF_Y, dp(96))
        }
        installDrag(card, layout)
        try {
            windowManager.addView(card, layout)
            root = card
            params = layout
        } catch (_: Exception) {
            root = null
            params = null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installDrag(view: View, layout: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = layout.x
                    startY = layout.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > dp(5) || abs(dy) > dp(5)) moved = true
                    val maxX = (context.resources.displayMetrics.widthPixels - touched.width).coerceAtLeast(0)
                    val maxY = (context.resources.displayMetrics.heightPixels - touched.height).coerceAtLeast(0)
                    layout.x = (startX + dx).coerceIn(0, maxX)
                    layout.y = (startY + dy).coerceIn(0, maxY)
                    try {
                        windowManager.updateViewLayout(touched, layout)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    prefs.edit().putInt(PREF_X, layout.x).putInt(PREF_Y, layout.y).apply()
                    if (!moved) touched.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun openApp() {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun hide() {
        val view = root ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
        root = null
        params = null
        levelText = null
        frequencyText = null
        statusDot = null
        cardBackground = null
        graphsView = null
        currentAccent = CYAN
    }

    private fun text(value: String, size: Float, color: Int, style: Int = Typeface.NORMAL) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        setTypeface(Typeface.DEFAULT, style)
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()
}

private class OverlayGraphsView(context: Context) : View(context) {
    companion object {
        private const val CYAN = 0xFF42E8D4.toInt()
        private const val GREEN = 0xFF69E58B.toInt()
        private const val AMBER = 0xFFFFC857.toInt()
        private const val RED = 0xFFFF6577.toInt()
        private const val GRID = 0xFF2B3651.toInt()
        private const val MUTED = 0xFFAEBAD4.toInt()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePath = Path()
    private val fillPath = Path()
    private var waveform = FloatArray(0)
    private var spectrum = FloatArray(0)
    private var accent = CYAN

    fun setData(newWaveform: FloatArray, newSpectrum: FloatArray, newAccent: Int) {
        waveform = newWaveform
        spectrum = newSpectrum
        accent = newAccent
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val widthPx = width.toFloat()
        val heightPx = height.toFloat()
        val waveTop = dp(12f)
        val waveBottom = heightPx * 0.47f
        val spectrumTop = heightPx * 0.61f
        val spectrumBottom = heightPx - dp(2f)

        paint.style = Paint.Style.FILL
        paint.textSize = dp(7.5f)
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.color = MUTED
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText("СИГНАЛ · 85 мс", 0f, dp(8f), paint)
        canvas.drawText("СПЕКТР · 20 Гц—20 кГц", 0f, heightPx * 0.57f, paint)

        paint.typeface = Typeface.DEFAULT
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(0.65f)
        paint.color = GRID
        val center = (waveTop + waveBottom) / 2f
        canvas.drawLine(0f, center, widthPx, center, paint)
        for (part in 1..3) {
            val x = widthPx * part / 4f
            canvas.drawLine(x, waveTop, x, waveBottom, paint)
        }
        for (part in 1..2) {
            val y = spectrumTop + (spectrumBottom - spectrumTop) * part / 3f
            canvas.drawLine(0f, y, widthPx, y, paint)
        }

        drawWaveform(canvas, waveTop, waveBottom, widthPx)
        drawSpectrum(canvas, spectrumTop, spectrumBottom, widthPx)
    }

    private fun drawWaveform(canvas: Canvas, top: Float, bottom: Float, widthPx: Float) {
        if (waveform.size < 2) return
        val center = (top + bottom) / 2f
        val halfHeight = (bottom - top) / 2f
        var maxAmplitude = 0.08f
        val pointCount = minOf(96, waveform.size)
        for (point in 0 until pointCount) {
            val source = point * (waveform.size - 1) / (pointCount - 1).coerceAtLeast(1)
            maxAmplitude = maxOf(maxAmplitude, abs(waveform[source]))
        }

        linePath.reset()
        fillPath.reset()
        fillPath.moveTo(0f, center)
        for (point in 0 until pointCount) {
            val source = point * (waveform.size - 1) / (pointCount - 1).coerceAtLeast(1)
            val value = waveform[source]
            val x = point.toFloat() / (pointCount - 1).coerceAtLeast(1) * widthPx
            val y = center - value.coerceIn(-maxAmplitude, maxAmplitude) / maxAmplitude * halfHeight * 0.9f
            if (point == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
            fillPath.lineTo(x, y)
        }
        fillPath.lineTo(widthPx, center)
        fillPath.close()

        paint.style = Paint.Style.FILL
        paint.color = accent
        paint.alpha = 45
        canvas.drawPath(fillPath, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.15f)
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = accent
        paint.alpha = 255
        canvas.drawPath(linePath, paint)
    }

    private fun drawSpectrum(canvas: Canvas, top: Float, bottom: Float, widthPx: Float) {
        if (spectrum.isEmpty()) return
        val barCount = minOf(36, spectrum.size)
        val barWidth = widthPx / barCount
        paint.style = Paint.Style.FILL
        for (index in 0 until barCount) {
            val start = index * spectrum.size / barCount
            val end = ((index + 1) * spectrum.size / barCount).coerceAtLeast(start + 1)
            var level = spectrum[start]
            for (source in start + 1 until minOf(end, spectrum.size)) level = maxOf(level, spectrum[source])
            val normalized = ((level - 20f) / 100f).coerceIn(0f, 1f)
            val left = index * barWidth + dp(0.25f)
            val barTop = bottom - normalized * (bottom - top)
            paint.color = when {
                level < 55f -> GREEN
                level < 75f -> CYAN
                level < 90f -> AMBER
                else -> RED
            }
            paint.alpha = 220
            canvas.drawRect(left, barTop, left + (barWidth - dp(0.45f)).coerceAtLeast(dp(0.5f)), bottom, paint)
        }
        paint.alpha = 255
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
