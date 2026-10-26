package com.arsmarthome.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Activity logos (Netflix, Prime, …) set in the Composer. Paths like
 * /local/netflix.png are resolved against Home Assistant. Kept on disk, so the
 * picker shows them instantly, even before Wi-Fi is back after a reboot.
 */
object Icons {
    private val memory = LruCache<String, Bitmap>(32)
    private val loading = HashSet<String>()
    private val failed = HashSet<String>()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private fun dir(ctx: Context) = File(ctx.filesDir, "icons").apply { mkdirs() }

    private fun key(path: String): String =
        MessageDigest.getInstance("MD5").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }

    /** The logo if it's ready; otherwise loads it and calls [onReady]. */
    fun get(ctx: Context, store: Store, path: String?, onReady: () -> Unit): Bitmap? {
        if (path.isNullOrBlank() || path in failed) return null
        memory.get(path)?.let { return it }
        val file = File(dir(ctx), key(path))
        if (file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath)?.let { memory.put(path, it); return it }
        }
        val base = store.webhookUrl ?: return null
        if (!loading.add(path)) return null
        worker.execute {
            val bmp = try {
                val conn = URL(URL(base), path).openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 10000
                val tmp = File(file.parentFile, "${file.name}.tmp")
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                conn.disconnect()
                BitmapFactory.decodeFile(tmp.absolutePath)?.also { tmp.renameTo(file) } ?: run { tmp.delete(); null }
            } catch (e: Exception) {
                null
            }
            main.post {
                loading.remove(path)
                if (bmp != null) {
                    memory.put(path, bmp)
                    onReady()
                } else {
                    failed.add(path)
                }
            }
        }
        return null
    }

    /** Drop cached logos that the project no longer uses. */
    fun prune(ctx: Context, keep: Collection<String>) {
        val wanted = keep.map { key(it) }.toSet()
        worker.execute {
            dir(ctx).listFiles()?.forEach { if (it.name !in wanted && !it.name.endsWith(".tmp")) it.delete() }
        }
        failed.clear()
    }
}
