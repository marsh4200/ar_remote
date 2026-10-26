package com.arsmarthome.remote

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Self-update without ADB: download the APK and hand it to PackageInstaller.
 * The USB install script grants "install unknown apps" once, so after that the
 * only thing needed on the remote is a tap on the system Install button.
 */
class Updater(private val context: Context, private val onStatus: (String) -> Unit) {
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile
    var busy = false
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(confirm)
                        onStatus("Tap Install to update AR Remote")
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> { busy = false; onStatus("Updated") }
                else -> {
                    busy = false
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "failed"
                    Log.w(HaLink.TAG, "update failed: $msg")
                    onStatus("Update failed: $msg")
                }
            }
        }
    }

    fun register() {
        context.registerReceiver(receiver, IntentFilter(ACTION))
    }

    fun unregister() {
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    fun install(url: String) {
        require(url.startsWith("https://") || url.startsWith("http://")) { "bad url" }
        check(!busy) { "update already running" }
        busy = true
        onStatus("Downloading update…")
        worker.execute {
            try {
                download(url)
            } catch (e: Exception) {
                busy = false
                Log.w(HaLink.TAG, "update download failed", e)
                onStatus("Update failed: ${e.message}")
            }
        }
    }

    private fun download(url: String) {
        var current = URL(url)
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            conn = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 60_000
                instanceFollowRedirects = false
            }
            val code = conn.responseCode
            if (code in 300..399 && hops < 5) {
                val next = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                current = URL(current, next)
                hops++
                continue
            }
            if (code >= 400) throw IllegalStateException("HTTP $code")
            break
        }

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            conn.inputStream.use { input ->
                session.openWrite("ar-remote.apk", 0, -1).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            conn.disconnect()
            val intent = Intent(ACTION).setPackage(context.packageName)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 31) flags = flags or FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            onStatus("Installing…")
            session.commit(pending.intentSender)
        } catch (e: Exception) {
            session.abandon()
            throw e
        } finally {
            session.close()
        }
    }

    companion object {
        const val ACTION = "com.arsmarthome.remote.INSTALL_STATUS"
        private const val FLAG_MUTABLE = 0x02000000 // PendingIntent.FLAG_MUTABLE (API 31)
    }
}
