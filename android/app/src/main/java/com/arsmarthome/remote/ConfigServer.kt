package com.arsmarthome.remote

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tiny HTTP server on :8080 that the integration programs the remote through.
 * Handlers run on the main thread; the socket thread waits for their answer.
 */
class ConfigServer(private val port: Int, private val routes: Routes) {

    interface Routes {
        fun info(): JSONObject
        fun link(body: JSONObject)
        fun project(body: JSONObject)
        fun state(body: JSONObject)
        fun navigate(body: JSONObject)
        fun update(body: JSONObject)
    }

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3)
    @Volatile private var socket: ServerSocket? = null

    fun start() {
        Thread({
            while (true) {
                try {
                    ServerSocket(port).use { server ->
                        socket = server
                        while (!server.isClosed) {
                            val client = server.accept()
                            pool.execute { handle(client) }
                        }
                    }
                } catch (e: Exception) {
                    if (socket?.isClosed == true && stopped) return@Thread
                    Log.w(HaLink.TAG, "server: ${e.message}")
                    Thread.sleep(2000)
                }
            }
        }, "ar-remote-server").apply { isDaemon = true }.start()
    }

    @Volatile private var stopped = false

    companion object {
        /** Projects are a few KB; anything far bigger is not from Home Assistant. */
        private const val MAX_BODY = 4 * 1024 * 1024
    }

    fun stop() {
        stopped = true
        socket?.close()
    }

    /**
     * Never let one bad request kill the app: an exception escaping a pool
     * thread is an uncaught exception, and Android ends the whole process.
     * (A client that hangs up mid-reply, a read timeout, a bogus length …)
     */
    private fun handle(client: Socket) {
        try {
            serve(client)
        } catch (e: Throwable) {
            Log.w(HaLink.TAG, "request failed: ${e.message}")
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun serve(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 8000
            val input = BufferedInputStream(sock.getInputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore('?')
            var length = 0
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("content-length:")) {
                    length = (line.substringAfter(':').trim().toIntOrNull() ?: 0).coerceIn(0, MAX_BODY)
                }
            }
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            val reply = try {
                route(method, path, if (length > 0) JSONObject(String(body, 0, read)) else JSONObject())
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: "error")
            }
            val bytes = reply.toString().toByteArray()
            val out = sock.getOutputStream()
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()
            )
            out.write(bytes)
            out.flush()
        }
    }

    private fun route(method: String, path: String, body: JSONObject): JSONObject = when {
        method == "GET" && (path == "/info" || path == "/") -> onMain { routes.info() }.put("ok", true)
        method == "POST" && path == "/link" -> ok { routes.link(body) }
        method == "POST" && path == "/project" -> ok { routes.project(body) }
        method == "POST" && path == "/state" -> ok { routes.state(body) }
        method == "POST" && path == "/navigate" -> ok { routes.navigate(body) }
        method == "POST" && path == "/update" -> ok { routes.update(body) }
        else -> JSONObject().put("ok", false).put("error", "unknown route $method $path")
    }

    private fun ok(block: () -> Unit): JSONObject {
        onMain { block(); JSONObject() }
        return JSONObject().put("ok", true)
    }

    private fun onMain(block: () -> JSONObject): JSONObject {
        var result: JSONObject? = null
        var error: Exception? = null
        val latch = CountDownLatch(1)
        main.post {
            try {
                result = block()
            } catch (e: Exception) {
                error = e
            }
            latch.countDown()
        }
        if (!latch.await(4, TimeUnit.SECONDS)) throw IllegalStateException("ui busy")
        error?.let { throw it }
        return result ?: JSONObject()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
            buf.write(b)
            if (buf.size() > 8192) return null
        }
    }
}
