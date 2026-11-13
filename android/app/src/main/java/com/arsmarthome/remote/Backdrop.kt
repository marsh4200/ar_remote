package com.arsmarthome.remote

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * The Residence look's background: a photo filling the screen (cropped, never
 * stretched) under a dark veil that deepens towards the bottom, so text and
 * icons stay readable on any picture.
 */
class Backdrop(private val photo: Bitmap) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val veil = Paint()
    private val fit = Matrix()

    override fun onBoundsChange(bounds: Rect) {
        val scale = maxOf(bounds.width().toFloat() / photo.width, bounds.height().toFloat() / photo.height)
        fit.setScale(scale, scale)
        fit.postTranslate(
            bounds.left + (bounds.width() - photo.width * scale) / 2f,
            bounds.top + (bounds.height() - photo.height * scale) / 2f,
        )
        veil.shader = LinearGradient(
            0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(),
            intArrayOf(0x9E0E0C0E.toInt(), 0xBD0E0C0E.toInt(), 0xE60A090A.toInt()),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        canvas.drawBitmap(photo, fit, paint)
        canvas.drawRect(bounds, veil)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
