package com.arsmarthome.remote

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Full-screen charging animation: a ring that fills to the battery level
 * (amber → green), a comet orbiting the ring, a breathing halo and a pulsing
 * bolt. Shown when the remote is put on charge; any key or touch dismisses it.
 */
class ChargingView(context: Context, private val logo: Bitmap?) : View(context) {

    private var level = 0
    private var charging = true
    private var shownLevel = 0f // animates up from 0 on appear
    private var phase = 0f // 0..1, loops

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Palette.track }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val comet = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bolt = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.text
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.muted
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        letterSpacing = 0.12f
    }
    private val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val boltPath = Path()
    private val box = RectF()
    private var gradient: SweepGradient? = null

    private val loop = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }
    private var fill: ValueAnimator? = null

    init {
        setBackgroundColor(Palette.ground)
        isClickable = true
    }

    fun update(levelPct: Int, isCharging: Boolean) {
        val target = levelPct.coerceIn(0, 100)
        charging = isCharging
        if (target != level || fill == null) {
            level = target
            fill?.cancel()
            fill = ValueAnimator.ofFloat(shownLevel, target.toFloat()).apply {
                duration = if (shownLevel == 0f) 1400 else 500
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener { shownLevel = it.animatedValue as Float }
                start()
            }
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loop.start()
    }

    override fun onDetachedFromWindow() {
        loop.cancel()
        fill?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val cx = w / 2f
        val cy = h * 0.47f
        gradient = SweepGradient(
            cx, cy,
            intArrayOf(Palette.accent, Color.parseColor("#F5D90A"), Palette.green, Palette.accent),
            floatArrayOf(0f, 0.45f, 0.9f, 1f),
        ).apply { setLocalMatrix(Matrix().apply { setRotate(-90f, cx, cy) }) }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h * 0.47f
        val r = min(w, h) * 0.30f
        val stroke = r * 0.10f
        val full = level >= 100 || !charging && level >= 95
        val breathe = (sin(phase * 2 * Math.PI) * 0.5 + 0.5).toFloat() // 0..1

        // Logo at the top.
        logo?.let { bmp ->
            val maxW = w * 0.42f
            val maxH = h * 0.10f
            val s = min(maxW / bmp.width, maxH / bmp.height)
            val bw = bmp.width * s
            val bh = bmp.height * s
            box.set(cx - bw / 2, h * 0.07f, cx + bw / 2, h * 0.07f + bh)
            canvas.drawBitmap(bmp, null, box, image)
        }

        // Breathing halo.
        val haloColor = if (full) Palette.green else Palette.accent
        for (i in 0..2) {
            val t = (phase + i / 3f) % 1f
            halo.strokeWidth = stroke * 0.25f
            halo.color = haloColor
            halo.alpha = ((1f - t) * 70).toInt()
            canvas.drawCircle(cx, cy, r + stroke + t * r * 0.35f, halo)
        }

        // Track and level arc.
        box.set(cx - r, cy - r, cx + r, cy + r)
        track.strokeWidth = stroke
        canvas.drawArc(box, 0f, 360f, false, track)
        val sweep = 360f * shownLevel / 100f
        ring.strokeWidth = stroke
        ring.shader = if (full) null else gradient
        ring.color = Palette.green
        if (sweep > 0.5f) canvas.drawArc(box, -90f, sweep, false, ring)

        // Comet orbiting the ring with a fading tail.
        if (charging && !full) {
            val head = phase * 360f - 90f
            comet.strokeWidth = stroke * 0.55f
            for (i in 0 until 14) {
                comet.color = Color.WHITE
                comet.alpha = (200 * (1f - i / 14f)).toInt()
                canvas.drawArc(box, head - i * 4f, 4.5f, false, comet)
            }
            val rad = Math.toRadians(head.toDouble())
            dot.color = Color.WHITE
            dot.alpha = 255
            canvas.drawCircle(cx + r * cos(rad).toFloat(), cy + r * sin(rad).toFloat(), stroke * 0.42f, dot)
        }

        // Spark at the tip of the level arc.
        if (sweep > 2f) {
            val tip = Math.toRadians((sweep - 90f).toDouble())
            dot.color = if (full) Palette.green else Palette.accent
            dot.alpha = (150 + 105 * breathe).toInt()
            canvas.drawCircle(cx + r * cos(tip).toFloat(), cy + r * sin(tip).toFloat(), stroke * (0.55f + 0.2f * breathe), dot)
        }

        // Pulsing bolt.
        val b = r * 0.30f * (1f + 0.07f * breathe)
        val by = cy - r * 0.22f
        boltPath.reset()
        boltPath.moveTo(cx + b * 0.15f, by - b)
        boltPath.lineTo(cx - b * 0.55f, by + b * 0.12f)
        boltPath.lineTo(cx - b * 0.02f, by + b * 0.12f)
        boltPath.lineTo(cx - b * 0.18f, by + b)
        boltPath.lineTo(cx + b * 0.55f, by - b * 0.15f)
        boltPath.lineTo(cx + b * 0.02f, by - b * 0.15f)
        boltPath.close()
        bolt.color = if (full) Palette.green else Palette.accent
        bolt.alpha = (190 + 65 * breathe).toInt()
        canvas.drawPath(boltPath, bolt)

        // Percentage and status.
        big.textSize = r * 0.42f
        canvas.drawText("${shownLevel.toInt()}%", cx, cy + r * 0.48f, big)
        small.textSize = r * 0.12f
        val status = when {
            full -> "FULLY CHARGED"
            charging -> "CHARGING"
            else -> "ON POWER"
        }
        canvas.drawText(status, cx, cy + r + stroke + r * 0.62f, small)
        small.textSize = r * 0.09f
        small.alpha = 140
        canvas.drawText("Press any key", cx, h * 0.93f, small)
        small.alpha = 255
    }
}
