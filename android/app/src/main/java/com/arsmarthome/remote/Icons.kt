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
    private val memory = LruCache<String, Bitmap>(64)
    private val loading = HashSet<String>()
    private val failed = HashSet<String>()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private fun dir(ctx: Context) = File(ctx.filesDir, "icons").apply { mkdirs() }

    private fun key(path: String): String =
        MessageDigest.getInstance("MD5").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }

    /** The logo if it's ready; otherwise loads it and calls [onReady]. */
    fun get(ctx: Context, store: Store, path: String?, onReady: () -> Unit): Bitmap? {
        if (path.isNullOrBlank() || path in failed || path.startsWith("icon:")) return null
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

    /**
     * The image with its plain background cut away (a white square tile, a
     * coloured app-icon tile) and trimmed to the icon itself — for icon-only
     * looks. Computed once per image and kept in memory.
     */
    fun getCut(ctx: Context, store: Store, path: String?, onReady: () -> Unit): Bitmap? {
        if (path.isNullOrBlank()) return null
        val k = "$path#cut"
        memory.get(k)?.let { return it }
        val src = get(ctx, store, path, onReady) ?: return null
        val cut = try { cutout(src) } catch (e: Exception) { src }
        memory.put(k, cut)
        return cut
    }

    private fun close(a: Int, b: Int, tol: Int): Boolean =
        Math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) <= tol &&
            Math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) <= tol &&
            Math.abs((a and 0xFF) - (b and 0xFF)) <= tol

    private fun diff(a: Int, b: Int): Int = maxOf(
        Math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
        Math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)),
        Math.abs((a and 0xFF) - (b and 0xFF)),
    )

    /**
     * Flood-fills the background in from the edges and makes it transparent:
     *  - a solid square (white, black, any one colour): the corners show it;
     *  - an app-icon tile with transparent rounded corners: the tile colour is
     *    found just inside the corners and along the edges.
     * The edge of the icon is softened so it doesn't look jagged. An image with
     * no plain background (a photo, a glyph already on transparent) is only trimmed.
     */
    fun cutout(input: Bitmap): Bitmap {
        val maxSide = 384
        var src = input
        if (maxOf(src.width, src.height) > maxSide) {
            val f = maxSide.toFloat() / maxOf(src.width, src.height)
            src = Bitmap.createScaledBitmap(src, maxOf(1, (src.width * f).toInt()), maxOf(1, (src.height * f).toInt()), true)
        }
        val w = src.width
        val h = src.height
        if (w < 8 || h < 8) return input
        val px = IntArray(w * h)
        src.copy(Bitmap.Config.ARGB_8888, false).getPixels(px, 0, w, 0, 0, w, h)
        fun a(c: Int) = (c ushr 24) and 0xFF
        fun at(x: Int, y: Int) = px[y * w + x]

        val corners = listOf(at(0, 0), at(w - 1, 0), at(0, h - 1), at(w - 1, h - 1))
        val tol = 46
        var bg: Int? = null
        if (corners.count { a(it) >= 200 } >= 3) {
            // A solid square: three or four corners the same colour.
            bg = corners.filter { a(it) >= 200 }.firstOrNull { c -> corners.count { a(it) >= 200 && close(it, c, tol) } >= 3 }
        } else if (corners.all { a(it) < 40 }) {
            // Transparent corners: an app-icon tile only if the edges' middles are one opaque colour.
            val inset = maxOf(2, minOf(w, h) / 30)
            val mids = listOf(at(w / 2, inset), at(w / 2, h - 1 - inset), at(inset, h / 2), at(w - 1 - inset, h / 2))
            val first = mids[0]
            if (mids.all { a(it) >= 200 && close(it, first, tol) }) bg = first
        }

        val key: Int? = bg
        val clear = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        fun passes(i: Int): Boolean {
            val c = px[i]
            return a(c) < 40 || (key != null && a(c) >= 40 && close(c, key, tol))
        }
        fun seed(x: Int, y: Int) {
            val i = y * w + x
            if (!clear[i] && passes(i)) { clear[i] = true; queue[tail++] = i }
        }
        for (x in 0 until w) { seed(x, 0); seed(x, h - 1) }
        for (y in 0 until h) { seed(0, y); seed(w - 1, y) }
        if (key != null) {
            while (head < tail) {
                val i = queue[head++]
                val x = i % w
                val y = i / w
                if (x > 0) seed(x - 1, y)
                if (x < w - 1) seed(x + 1, y)
                if (y > 0) seed(x, y - 1)
                if (y < h - 1) seed(x, y + 1)
            }
        }

        if (key != null) {
            // Background showing through inside the icon (the middle of an outline
            // TV, the hole of an "O"): the same colour, closer match, goes too.
            for (i in px.indices) if (!clear[i] && a(px[i]) >= 40 && diff(px[i], key) <= 22) clear[i] = true
        }
        val out = px.copyOf()
        var kept = 0
        for (i in out.indices) {
            if (clear[i]) { out[i] = 0; continue }
            if (a(out[i]) >= 16) kept++
        }
        // Nothing (or almost nothing) left: the "background" was the icon. Leave it.
        if (key != null && kept < w * h / 50) return input
        if (key != null) {
            // Soften the rim: pixels touching the cut and still close to the
            // background colour fade out with how close they are.
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (clear[i]) continue
                val touches = (x > 0 && clear[i - 1]) || (x < w - 1 && clear[i + 1]) ||
                    (y > 0 && clear[i - w]) || (y < h - 1 && clear[i + w])
                if (!touches) continue
                val d = diff(out[i], key)
                if (d < tol * 3) {
                    val keepA = a(out[i]) * (d - tol).coerceAtLeast(0) / (tol * 2)
                    out[i] = (keepA.coerceIn(0, 255) shl 24) or (out[i] and 0xFFFFFF)
                }
            }
        }
        if (key != null) {
            // A plain black / dark grey icon would vanish on the remote's dark
            // screen: draw it white. Coloured logos keep their colours.
            var dark = true
            for (c in out) {
                if (a(c) < 128) continue
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                if (maxOf(r, g, b) > 90 || maxOf(r, g, b) - minOf(r, g, b) > 30) { dark = false; break }
            }
            if (dark) for (i in out.indices) if (a(out[i]) > 0) out[i] = (out[i] and 0xFF000000.toInt()) or 0xF3F4F6
        }
        // Trim to the icon, with a little air around it.
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (a(out[y * w + x]) >= 16) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return input
        val pad = maxOf(1, maxOf(maxX - minX, maxY - minY) / 40)
        minX = maxOf(0, minX - pad); minY = maxOf(0, minY - pad)
        maxX = minOf(w - 1, maxX + pad); maxY = minOf(h - 1, maxY + pad)
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        full.setPixels(out, 0, w, 0, 0, w, h)
        return Bitmap.createBitmap(full, minX, minY, maxX - minX + 1, maxY - minY + 1)
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
