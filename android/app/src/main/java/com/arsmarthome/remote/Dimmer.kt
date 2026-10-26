package com.arsmarthome.remote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/**
 * A dimmer you drag with your finger. Shows the level while dragging, sends it
 * every ~300 ms while you hold it and once more when you let go.
 * D-pad LEFT / RIGHT still dims in 10 % steps (handled by the Lights screen).
 */
class DimmerView(
    context: Context,
    level: Int,
    private val onLevel: (level: Int, final: Boolean) -> Unit,
) : View(context) {

    var level: Int = level.coerceIn(0, 100)
        private set

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.track }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.accent }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.text }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.accent
        style = Paint.Style.STROKE
    }
    private val box = RectF()
    private var dragging = false
    private var lastSent = 0L

    init {
        isClickable = true
        contentDescription = "Dimmer"
    }

    /** Called by the screen when HA reports a new level (ignored mid-drag). */
    fun show(newLevel: Int) {
        if (!dragging) { level = newLevel.coerceIn(0, 100); invalidate() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = resolveSize(context.dp(36), heightMeasureSpec) // big enough for a thumb
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    private val pad get() = height / 2f

    private fun levelAt(x: Float): Int {
        val usable = width - 2 * pad
        if (usable <= 0) return level
        return (((x - pad) / usable) * 100f).toInt().coerceIn(0, 100)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true) // don't let the page scroll
                update(levelAt(event.x), final = false)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) update(levelAt(event.x), final = false)
            MotionEvent.ACTION_UP -> if (dragging) {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                update(levelAt(event.x), final = true)
            }
            MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onLevel(level, true)
            }
        }
        return true
    }

    private fun update(newLevel: Int, final: Boolean) {
        level = newLevel
        invalidate()
        val now = SystemClock.uptimeMillis()
        if (final || now - lastSent >= 300) {
            lastSent = now
            onLevel(level, final)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val cy = h / 2f
        val barH = context.dp(8).toFloat()
        val left = pad
        val right = width - pad
        box.set(left, cy - barH / 2, right, cy + barH / 2)
        canvas.drawRoundRect(box, barH / 2, barH / 2, track)
        val x = left + (right - left) * level / 100f
        if (level > 0) {
            box.set(left, cy - barH / 2, x, cy + barH / 2)
            canvas.drawRoundRect(box, barH / 2, barH / 2, fill)
        }
        val r = h * (if (dragging) 0.40f else 0.32f)
        canvas.drawCircle(x, cy, r, thumb)
        if (dragging) {
            ring.strokeWidth = context.dp(3).toFloat()
            canvas.drawCircle(x, cy, r + context.dp(3), ring)
        }
    }
}
