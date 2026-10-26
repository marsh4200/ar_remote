package com.arsmarthome.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Album art / now-playing pictures from HA's media proxy, cached in memory. */
object Artwork {
    private val cache = LruCache<String, Bitmap>(8)
    private val loading = HashSet<String>()
    private val failed = HashSet<String>()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Returns the bitmap if cached; otherwise starts loading and calls [onReady] when done. */
    fun get(url: String?, onReady: () -> Unit): Bitmap? {
        if (url.isNullOrEmpty() || url in failed) return null
        cache.get(url)?.let { return it }
        if (loading.add(url)) {
            worker.execute {
                val bmp = try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 4000
                    conn.readTimeout = 8000
                    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                    conn.inputStream.use { BitmapFactory.decodeStream(it, null, opts) }.also { conn.disconnect() }
                } catch (e: Exception) {
                    null
                }
                main.post {
                    loading.remove(url)
                    if (bmp != null) {
                        cache.put(url, bmp)
                        onReady()
                    } else {
                        failed.add(url)
                    }
                }
            }
        }
        return null
    }
}
