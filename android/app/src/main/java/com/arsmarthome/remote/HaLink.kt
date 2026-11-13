package com.arsmarthome.remote

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Fire messages at the integration's webhook. One worker keeps presses in order. */
class HaLink(private val store: Store, private val remoteId: String) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var lastOk = false
        private set

    /** Called on the main thread after every message: did it get through, and its type. */
    var onResult: ((ok: Boolean, type: String) -> Unit)? = null

    fun send(message: JSONObject, onReply: ((JSONObject) -> Unit)? = null) {
        val url = store.webhookUrl ?: return
        message.put("remote", remoteId)
        worker.execute {
            val reply = post(url, message)
            val ok = lastOk
            val type = message.optString("type")
            main.post {
                if (reply != null && onReply != null) onReply(reply)
                onResult?.invoke(ok, type)
            }
        }
    }

    private fun post(url: String, body: JSONObject): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 6000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            lastOk = code < 400
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (e: Exception) {
            lastOk = false
            Log.w(TAG, "webhook ${body.optString("type")} failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        const val TAG = "ArRemote"
    }
}
