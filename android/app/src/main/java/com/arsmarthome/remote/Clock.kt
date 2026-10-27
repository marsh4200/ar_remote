package com.arsmarthome.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Screensaver: your logo is the clock face, hands turn around the middle of its
 * dial, the date sits underneath. Shown on the charger after a while without a
 * key press. Drifts a few pixels every minute so nothing burns into the screen.
 *
 * [dial] = where the dial's centre is in the logo (fractions of its width /
 * height) and the dial radius (fraction of its width). For the built-in A&R
 * logo that's the circle around the house; for your own logo it's the middle.
 */
class ClockView(
    context: Context,
    private val logo: Bitmap?,
    private val dial: FloatArray,
) : View(context) {

    private val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val hand = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.muted
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        letterSpacing = 0.06f
    }
    private val box = RectF()
    private val blue = Color.parseColor("#18B4F0")
    private val edge = Color.parseColor("#05121C")
    private val dateFormat = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())

    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }

    init {
        setBackgroundColor(Color.parseColor("#05070A"))
        isClickable = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val now = Calendar.getInstance()
        // Burn-in protection: wander up to ±12 px, one step a minute.
        val minuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val drift = context.dp(12).toFloat()
        val dx = (sin(minuteOfDay * 0.37) * drift).toFloat()
        val dy = (cos(minuteOfDay * 0.23) * drift).toFloat()

        // Logo (face), as large as fits, a little above the middle.
        val maxW = w * 0.92f
        val maxH = h * 0.72f
        val lw: Float
        val lh: Float
        if (logo != null) {
            val s = min(maxW / logo.width, maxH / logo.height)
            lw = logo.width * s
            lh = logo.height * s
        } else {
            lw = min(maxW, maxH); lh = lw
        }
        val left = (w - lw) / 2f + dx
        val top = (h - lh) / 2f - h * 0.04f + dy
        box.set(left, top, left + lw, top + lh)
        logo?.let { canvas.drawBitmap(it, null, box, image) }

        // Hands around the dial centre.
        val cx = left + lw * dial[0]
        val cy = top + lh * dial[1]
        val r = lw * dial[2]
        val sec = now.get(Calendar.SECOND)
        val min = now.get(Calendar.MINUTE) + sec / 60f
        val hr = now.get(Calendar.HOUR) + min / 60f
        drawHand(canvas, cx, cy, hr * 30f, r * 0.50f, 0f, r * 0.055f, Color.WHITE)
        drawHand(canvas, cx, cy, min * 6f, r * 0.80f, 0f, r * 0.036f, Color.WHITE)
        drawHand(canvas, cx, cy, sec * 6f, r * 0.86f, r * 0.15f, r * 0.018f, blue, outline = false)
        dot.color = edge
        canvas.drawCircle(cx, cy, r * 0.075f, dot)
        dot.color = blue
        canvas.drawCircle(cx, cy, r * 0.055f, dot)

        // Date under the logo.
        text.textSize = min(w, h) * 0.045f
        canvas.drawText(dateFormat.format(now.time), w / 2f + dx, min(h - text.textSize, box.bottom + text.textSize * 1.8f), text)
    }

    private fun drawHand(
        canvas: Canvas, cx: Float, cy: Float, deg: Float, length: Float, tail: Float,
        width: Float, color: Int, outline: Boolean = true,
    ) {
        val a = Math.toRadians(deg.toDouble())
        val ex = cx + (sin(a) * length).toFloat()
        val ey = cy - (cos(a) * length).toFloat()
        val tx = cx - (sin(a) * tail).toFloat()
        val ty = cy + (cos(a) * tail).toFloat()
        if (outline) {
            hand.color = edge
            hand.strokeWidth = width * 1.7f
            canvas.drawLine(tx, ty, ex, ey, hand)
        }
        hand.color = color
        hand.strokeWidth = width
        canvas.drawLine(tx, ty, ex, ey, hand)
    }

    companion object {
        /** Dial of the built-in A&R Smart Home logo (circle around the house). */
        val BUILT_IN_DIAL = floatArrayOf(0.504f, 0.371f, 0.422f)
        /** Your own logo: hands in the middle. */
        val CENTRE_DIAL = floatArrayOf(0.5f, 0.5f, 0.42f)
    }
}
