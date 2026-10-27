package com.arsmarthome.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Your logo, set in the Composer (Remote settings). Downloaded once and kept on
 * the remote, so the splash shows it straight after a reboot, before Wi-Fi is up.
 */
object Logo {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var cached: Bitmap? = null
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "logo.img")

    private var builtIn: Bitmap? = null

    /** True when a logo from Home Assistant is in use (not the built-in one). */
    fun isCustom(ctx: Context): Boolean {
        bitmap(ctx)
        return cached != null
    }

    /** The logo set in Home Assistant, or the built-in A&R Smart Home logo. */
    fun bitmap(ctx: Context): Bitmap? {
        if (!loaded) {
            loaded = true
            val f = file(ctx)
            cached = if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
        }
        return cached ?: builtIn ?: try {
            BitmapFactory.decodeResource(ctx.resources, R.drawable.ar_logo).also { builtIn = it }
        } catch (e: Exception) {
            null
        }
    }

    /** Fetch [path] (absolute, or relative to HA like /local/logo.png) if it changed. */
    fun sync(ctx: Context, store: Store, path: String?, onChanged: () -> Unit) {
        if (path.isNullOrBlank()) {
            if (store.logoUrl != null) {
                file(ctx).delete()
                store.logoUrl = null
                cached = null
                onChanged()
            }
            return
        }
        if (path == store.logoUrl && file(ctx).exists()) return
        val base = store.webhookUrl ?: return
        val url = try { URL(URL(base), path) } catch (e: Exception) { return }
        val target = file(ctx)
        worker.execute {
            val ok = try {
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 15000
                val tmp = File(target.parentFile, "logo.tmp")
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                conn.disconnect()
                val bmp = BitmapFactory.decodeFile(tmp.absolutePath)
                if (bmp == null) { tmp.delete(); null } else { tmp.renameTo(target); bmp }
            } catch (e: Exception) {
                Log.w(HaLink.TAG, "logo $url failed: ${e.message}")
                null
            }
            if (ok != null) main.post {
                cached = ok
                loaded = true
                store.logoUrl = path
                onChanged()
            }
        }
    }
}

/** Start-up splash: the logo fades and settles in, holds, then fades away. */
class SplashView(context: Context, private val logo: Bitmap?) : View(context) {
    private val box = RectF()
    private val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val fallback = context.getDrawable(R.drawable.ic_launcher)

    init {
        setBackgroundColor(Palette.ground)
        isClickable = true
    }

    private var intro: android.animation.ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        replay()
    }

    override fun onDetachedFromWindow() {
        intro?.cancel()
        super.onDetachedFromWindow()
    }

    /** Play the fade-and-settle intro (again), e.g. once the boot animation is gone. */
    fun replay() {
        intro?.cancel()
        logoScale = 0.86f
        logoAlpha = 0f
        invalidate()
        intro = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener {
                val t = it.animatedValue as Float
                logoScale = 0.86f + 0.14f * t
                logoAlpha = t
                invalidate()
            }
            start()
        }
    }

    private var logoScale = 1f
    private var logoAlpha = 1f

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val maxW = w * 0.62f * logoScale
        val maxH = h * 0.32f * logoScale
        val cx = w / 2f
        val cy = h * 0.46f
        if (logo != null) {
            val s = minOf(maxW / logo.width, maxH / logo.height)
            val bw = logo.width * s
            val bh = logo.height * s
            box.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
            paint.alpha = (255 * logoAlpha).toInt()
            canvas.drawBitmap(logo, null, box, paint)
        } else if (fallback != null) {
            val size = (minOf(w, h) * 0.30f * logoScale).toInt()
            fallback.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
            fallback.alpha = (255 * logoAlpha).toInt()
            fallback.draw(canvas)
        }
    }
}
