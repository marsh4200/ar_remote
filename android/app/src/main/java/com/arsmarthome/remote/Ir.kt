package com.arsmarthome.remote

import android.content.Context
import android.hardware.ConsumerIrManager
import android.util.Base64
import android.util.Log
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The remote's own IR blaster (Android's ConsumerIrManager).
 *
 * Codes are text, in any of these forms:
 *  - Pronto hex:        "0000 006D 0022 0002 0157 00AC …" (IR databases, Remote Central)
 *  - Broadlink base64:  "JgBQAAABJ5MUEhQ2…" (codes learned by a Broadlink in Home Assistant)
 *  - NEC:               "nec:0x04:0x08"  (address : command; a 16-bit address = extended NEC)
 *  - Samsung:           "samsung:0x07:0x02"
 *  - Raw:               "raw:38000:9000,4500,560,560,…" (carrier Hz : on/off times in µs)
 */
object Ir {
    class Signal(val freq: Int, val pattern: IntArray)

    private val worker = Executors.newSingleThreadExecutor()

    private fun manager(ctx: Context): ConsumerIrManager? =
        ctx.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

    fun available(ctx: Context): Boolean = try {
        manager(ctx)?.hasIrEmitter() == true
    } catch (e: Exception) {
        false
    }

    /** Carrier ranges the blaster supports, e.g. "30000-60000". */
    fun frequencies(ctx: Context): List<String> = try {
        manager(ctx)?.carrierFrequencies?.map { "${it.minFrequency}-${it.maxFrequency}" } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    /** Send now, on the calling thread (blocks while the code is transmitted). */
    fun send(ctx: Context, code: String, repeat: Int = 1) {
        val ir = manager(ctx)
        if (ir == null || !ir.hasIrEmitter()) throw IllegalStateException("this remote has no IR blaster")
        val signal = parse(code)
        repeat(repeat.coerceIn(1, 20)) { ir.transmit(signal.freq, signal.pattern) }
    }

    /** Send from a key press without holding up the screen. */
    fun sendAsync(ctx: Context, code: String) {
        val app = ctx.applicationContext
        worker.execute {
            try {
                send(app, code)
            } catch (e: Exception) {
                Log.w(HaLink.TAG, "IR send failed: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------- parsing

    fun parse(code: String): Signal {
        val c = code.trim()
        val lower = c.lowercase()
        return when {
            lower.startsWith("nec:") -> nec(c.substring(4))
            lower.startsWith("samsung:") -> samsung(c.substring(8))
            lower.startsWith("raw:") -> raw(c.substring(4))
            lower.startsWith("pronto:") -> pronto(c.substring(7))
            lower.startsWith("b64:") -> broadlink(c.substring(4))
            Regex("^[0-9a-fA-F]{4}(\\s+[0-9a-fA-F]{4})+$").matches(c) -> pronto(c)
            else -> broadlink(c)
        }
    }

    private fun num(s: String): Int {
        val t = s.trim().lowercase()
        return if (t.startsWith("0x")) t.substring(2).toInt(16) else t.toInt()
    }

    private fun pronto(text: String): Signal {
        val w = text.trim().split(Regex("\\s+")).map { it.toInt(16) }
        require(w.size >= 6 && w[0] == 0x0000) { "only raw Pronto codes (starting 0000) are supported" }
        val freq = (1_000_000.0 / (w[1] * 0.241246)).roundToInt()
        val once = w[2]
        val again = w[3]
        val period = 1_000_000.0 / freq
        val bursts = w.drop(4)
        require(bursts.size >= 2 * (once + again)) { "Pronto code is shorter than its header says" }
        // The "once" part, then the "repeat" part once (the key press itself).
        val take = if (once > 0) 2 * once + 2 * again else 2 * again
        val pattern = bursts.take(take).map { (it * period).roundToInt().coerceAtLeast(1) }
        return Signal(freq, pattern.toIntArray())
    }

    private fun broadlink(b64: String): Signal {
        val bytes = try {
            Base64.decode(b64.trim(), Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("not a code this remote understands")
        }
        require(bytes.size > 4 && (bytes[0].toInt() and 0xFF) == 0x26) { "not a Broadlink IR code (RF codes can't be sent by the remote)" }
        val len = (bytes[2].toInt() and 0xFF) or ((bytes[3].toInt() and 0xFF) shl 8)
        val end = minOf(bytes.size, 4 + len)
        val out = ArrayList<Int>()
        var i = 4
        while (i < end) {
            var v = bytes[i].toInt() and 0xFF
            i++
            if (v == 0 && i + 1 < end) {
                v = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
                i += 2
            }
            if (v == 0) continue
            // Broadlink ticks are 269/8192 ms (about 32.8 µs).
            out += (v * 269_000L / 8192L).toInt()
        }
        // The end marker (0x0d05) is a long gap; drop a trailing gap so the pattern ends "on".
        if (out.size % 2 == 0 && out.isNotEmpty()) out.removeAt(out.size - 1)
        require(out.isNotEmpty()) { "empty Broadlink code" }
        return Signal(38_000, out.toIntArray())
    }

    private fun raw(text: String): Signal {
        val freq = num(text.substringBefore(':'))
        val pattern = text.substringAfter(':').split(',', ' ').filter { it.isNotBlank() }.map { it.trim().toInt() }
        require(pattern.isNotEmpty()) { "raw code has no timings" }
        return Signal(freq, pattern.toIntArray())
    }

    private fun bits(out: MutableList<Int>, value: Int, count: Int, mark: Int, zero: Int, one: Int) {
        for (b in 0 until count) {
            out += mark
            out += if ((value shr b) and 1 == 1) one else zero
        }
    }

    private fun nec(text: String): Signal {
        val parts = text.split(':')
        require(parts.size == 2) { "NEC code is nec:<address>:<command>" }
        val addr = num(parts[0])
        val cmd = num(parts[1]) and 0xFF
        val p = mutableListOf(9000, 4500)
        if (addr > 0xFF) bits(p, addr and 0xFFFF, 16, 560, 560, 1690)
        else { bits(p, addr, 8, 560, 560, 1690); bits(p, addr.inv() and 0xFF, 8, 560, 560, 1690) }
        bits(p, cmd, 8, 560, 560, 1690)
        bits(p, cmd.inv() and 0xFF, 8, 560, 560, 1690)
        p += 560
        return Signal(38_000, p.toIntArray())
    }

    private fun samsung(text: String): Signal {
        val parts = text.split(':')
        require(parts.size == 2) { "Samsung code is samsung:<address>:<command>" }
        val addr = num(parts[0]) and 0xFF
        val cmd = num(parts[1]) and 0xFF
        val p = mutableListOf(4500, 4500)
        bits(p, addr, 8, 560, 560, 1690)
        bits(p, addr, 8, 560, 560, 1690)
        bits(p, cmd, 8, 560, 560, 1690)
        bits(p, cmd.inv() and 0xFF, 8, 560, 560, 1690)
        p += 560
        return Signal(38_000, p.toIntArray())
    }
}
