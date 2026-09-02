package com.playeverywhere.noiselog.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.log10

private const val CYAN = 0xFF42E8D4.toInt()
private const val GREEN = 0xFF69E58B.toInt()
private const val AMBER = 0xFFFFC857.toInt()
private const val RED = 0xFFFF6577.toInt()
private const val GRID = 0xFF2B3651.toInt()
private const val TEXT = 0xFFAEBAD4.toInt()

class MeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcBounds = RectF()
    private var db = 0.0
    private var running = false

    fun setLevel(value: Double, isRunning: Boolean) {
        db = value
        running = isRunning
        contentDescription = if (isRunning) "Уровень шума %.1f децибел A".format(value) else "Измерение остановлено"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height * 0.53f
        val radius = minOf(width * 0.34f, height * 0.38f)
        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = dp(12f)
        paint.color = 0xFF25314B.toInt()
        canvas.drawArc(arcBounds, 145f, 250f, false, paint)

        val fraction = ((db - 20.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
        paint.color = levelColor(db)
        if (running) canvas.drawArc(arcBounds, 145f, 250f * fraction, false, paint)

        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
        paint.textSize = dp(54f)
        paint.color = Color.WHITE
        canvas.drawText(if (running) "%.1f".format(db) else "—", cx, cy + dp(12f), paint)
        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.textSize = dp(14f)
        paint.color = TEXT
        canvas.drawText("дБ(A) · оценка", cx, cy + dp(38f), paint)

        paint.textSize = dp(12f)
        paint.color = if (running) levelColor(db) else TEXT
        canvas.drawText(if (running) levelName(db) else "НАЖМИТЕ «НАЧАТЬ»", cx, height - dp(13f), paint)
    }

    private fun levelColor(value: Double) = when {
        value < 55 -> GREEN
        value < 70 -> CYAN
        value < 85 -> AMBER
        else -> RED
    }

    private fun levelName(value: Double) = when {
        value < 35 -> "ОЧЕНЬ ТИХО"
        value < 55 -> "ТИХО"
        value < 70 -> "УМЕРЕННО"
        value < 85 -> "ГРОМКО"
        else -> "ОЧЕНЬ ГРОМКО"
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val waveformPath = Path()
    private val waveformFillPath = Path()
    private var values = FloatArray(0)

    fun setWaveform(waveform: FloatArray) {
        values = waveform
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val center = height / 2f
        paint.color = GRID
        paint.strokeWidth = dp(1f)
        canvas.drawLine(0f, center, width.toFloat(), center, paint)
        if (values.size < 2) return
        waveformPath.reset()
        waveformFillPath.reset()
        val scale = height * 0.42f
        waveformFillPath.moveTo(0f, center)
        for (index in values.indices) {
            val x = index.toFloat() / (values.size - 1) * width
            val y = center - values[index].coerceIn(-1f, 1f) * scale
            if (index == 0) waveformPath.moveTo(x, y) else waveformPath.lineTo(x, y)
            waveformFillPath.lineTo(x, y)
        }
        waveformFillPath.lineTo(width.toFloat(), center)
        waveformFillPath.close()
        paint.style = Paint.Style.FILL
        paint.color = 0x5542E8D4
        canvas.drawPath(waveformFillPath, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = CYAN
        canvas.drawPath(waveformPath, paint)
        paint.style = Paint.Style.FILL
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}

class SpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var levels = FloatArray(0)
    private var frequencies = FloatArray(0)

    fun setSpectrum(values: FloatArray, axis: FloatArray) {
        levels = values
        frequencies = axis
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val chartBottom = height - dp(22f)
        val chartTop = dp(8f)
        paint.strokeWidth = dp(1f)
        paint.textSize = dp(10f)
        paint.textAlign = Paint.Align.CENTER
        listOf(20f to "20", 100f to "100", 1000f to "1k", 10_000f to "10k", 20_000f to "20k").forEach { (frequency, label) ->
            val x = logX(frequency)
            paint.color = GRID
            canvas.drawLine(x, chartTop, x, chartBottom, paint)
            paint.color = TEXT
            canvas.drawText(label, x, height - dp(5f), paint)
        }
        if (levels.isEmpty()) return
        val barWidth = width.toFloat() / levels.size
        for (index in levels.indices) {
            val normalized = ((levels[index] - 20f) / 100f).coerceIn(0f, 1f)
            val left = index * barWidth + dp(0.5f)
            val top = chartBottom - normalized * (chartBottom - chartTop)
            paint.color = when {
                levels[index] < 55f -> GREEN
                levels[index] < 75f -> CYAN
                levels[index] < 90f -> AMBER
                else -> RED
            }
            paint.alpha = 210
            canvas.drawRoundRect(left, top, left + barWidth - dp(1f), chartBottom, dp(1.5f), dp(1.5f), paint)
        }
        paint.alpha = 255
    }

    private fun logX(frequency: Float): Float {
        val ratio = (log10(frequency.coerceAtLeast(20f)) - log10(20f)) / (log10(20_000f) - log10(20f))
        return ratio * width
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}

class HistoryChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val historyPath = Path()
    private var values = FloatArray(0)

    fun setHistory(history: FloatArray) {
        values = history
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.textSize = dp(9f)
        paint.textAlign = Paint.Align.RIGHT
        listOf(40f, 70f, 100f).forEach { level ->
            val y = levelToY(level)
            paint.color = GRID
            canvas.drawLine(dp(26f), y, width.toFloat(), y, paint)
            paint.color = TEXT
            canvas.drawText(level.toInt().toString(), dp(23f), y + dp(3f), paint)
        }
        if (values.size < 2) return
        historyPath.reset()
        val left = dp(28f)
        for (index in values.indices) {
            val x = left + index.toFloat() / (values.size - 1) * (width - left)
            val y = levelToY(values[index])
            if (index == 0) historyPath.moveTo(x, y) else historyPath.lineTo(x, y)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = CYAN
        canvas.drawPath(historyPath, paint)
        paint.style = Paint.Style.FILL
    }

    private fun levelToY(level: Float): Float {
        val normalized = ((level - 20f) / 100f).coerceIn(0f, 1f)
        return height - dp(8f) - normalized * (height - dp(16f))
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}
