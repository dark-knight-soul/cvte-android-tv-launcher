package io.github.darkknightsoul.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Круглые стрелочные часы для заставки. Перерисовываются раз в секунду,
 * все кисти создаются заранее — в onDraw нет выделения памяти.
 */
class AnalogClockView(context: Context) : View(context) {

    private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 0, 0, 0) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = white(0.55f); strokeWidth = context.dp(2).toFloat()
    }
    private val tickMinute = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = white(0.35f); strokeWidth = context.dp(1.5f).toFloat(); strokeCap = Paint.Cap.ROUND
    }
    private val tickHour = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = white(0.95f); strokeWidth = context.dp(4).toFloat(); strokeCap = Paint.Cap.ROUND
    }
    private val hourHand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = context.dp(8).toFloat(); strokeCap = Paint.Cap.ROUND
    }
    private val minuteHand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = context.dp(5).toFloat(); strokeCap = Paint.Cap.ROUND
    }
    private val secondHand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 181, 71); strokeWidth = context.dp(2).toFloat(); strokeCap = Paint.Cap.ROUND
    }
    private val center = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 181, 71) }
    private val calendar = Calendar.getInstance()

    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            // Следующая перерисовка — ровно в начале следующей секунды
            postDelayed(this, 1000 - System.currentTimeMillis() % 1000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(tick)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(tick)
        dial?.recycle()
        dial = null
    }

    /** Циферблат (круг и 60 рисок) рисуется один раз в картинку — на каждом кадре это одна текстура. */
    private var dial: Bitmap? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        dial?.recycle()
        dial = if (w > 0 && h > 0) Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { drawDial(Canvas(it), w, h) } else null
    }

    private fun drawDial(canvas: Canvas, w: Int, h: Int) {
        val cx = w / 2f
        val cy = h / 2f
        val r = min(cx, cy) - ring.strokeWidth
        canvas.drawCircle(cx, cy, r, face)
        canvas.drawCircle(cx, cy, r, ring)
        // Риски: 60 минутных и 12 часовых
        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0)
            val hour = i % 5 == 0
            val inner = r * if (hour) 0.80f else 0.88f
            val outer = r * 0.93f
            val s = sin(a).toFloat(); val c = cos(a).toFloat()
            canvas.drawLine(cx + inner * s, cy - inner * c, cx + outer * s, cy - outer * c, if (hour) tickHour else tickMinute)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - ring.strokeWidth
        dial?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        calendar.timeInMillis = System.currentTimeMillis()
        val sec = calendar.get(Calendar.SECOND)
        val min = calendar.get(Calendar.MINUTE) + sec / 60f
        val hour = calendar.get(Calendar.HOUR) + min / 60f
        hand(canvas, cx, cy, hour * 30f, r * 0.50f, 0f, hourHand)
        hand(canvas, cx, cy, min * 6f, r * 0.74f, 0f, minuteHand)
        hand(canvas, cx, cy, sec * 6f, r * 0.84f, r * 0.16f, secondHand)
        canvas.drawCircle(cx, cy, hourHand.strokeWidth * 0.8f, center)
    }

    /** Стрелка под углом [deg] (0 — вверх), длиной [length] и «хвостом» [tail] в обратную сторону. */
    private fun hand(canvas: Canvas, cx: Float, cy: Float, deg: Float, length: Float, tail: Float, paint: Paint) {
        val a = Math.toRadians(deg.toDouble())
        val s = sin(a).toFloat(); val c = cos(a).toFloat()
        canvas.drawLine(cx - tail * s, cy + tail * c, cx + length * s, cy - length * c, paint)
    }
}
