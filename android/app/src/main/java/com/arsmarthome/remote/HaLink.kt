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

    fun send(message: JSONObject, onReply: ((JSONObject) -> Unit)? = null) {
        val url = store.webhookUrl ?: return
        message.put("remote", remoteId)
        worker.execute {
            val reply = post(url, message)
            if (reply != null && onReply != null) main.post { onReply(reply) }
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
