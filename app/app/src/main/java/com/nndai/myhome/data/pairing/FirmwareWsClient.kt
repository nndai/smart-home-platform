package com.nndai.myhome.data.pairing

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class FirmwareWsClient(host: String, port: Int = 82) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()
    private val url = "ws://$host:$port"
    private val json = Json { ignoreUnknownKeys = true }

    private var webSocket: WebSocket? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val connected = CompletableDeferred<Unit>()
    private var eventHandler: ((JsonObject) -> Unit)? = null

    suspend fun connect(): Boolean {
        Log.d(TAG, "connect(): opening WS to $url")
        try {
            client.newWebSocket(Request.Builder().url(url).build(), listener)
            withTimeout(10_000) { connected.await() }
            Log.d(TAG, "connect(): WS connected to $url")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "connect(): FAILED to $url — ${e::class.simpleName}: ${e.message}", e)
            return false
        }
    }

    suspend fun request(cmd: String, payload: JsonObject? = null, timeoutMs: Long = 20_000): JsonObject? {
        val reqId = UUID.randomUUID().toString().take(8)
        val deferred = CompletableDeferred<JsonObject>()
        pending[reqId] = deferred
        val message = buildJsonObject {
            put("cmd", cmd)
            put("reqId", reqId)
            payload?.let { put("payload", it) }
        }
        Log.d(TAG, "request(): sending cmd='$cmd' reqId=$reqId payload=${payload ?: "{}"}")
        val sent = webSocket?.send(message.toString()) ?: false
        if (!sent) {
            Log.e(TAG, "request(): send FAILED (socket null or closed) cmd='$cmd'")
            pending.remove(reqId)
            return null
        }
        return try {
            val resp = withTimeout(timeoutMs) { deferred.await() }
            Log.d(TAG, "request(): resp for '$cmd': $resp")
            resp
        } catch (e: Exception) {
            Log.e(TAG, "request(): timeout/exception for '$cmd' — ${e.message}")
            pending.remove(reqId)
            null
        }
    }

    fun setEventHandler(handler: (JsonObject) -> Unit) {
        eventHandler = handler
    }

    fun close() {
        runCatching { webSocket?.close(1000, "done") }
        webSocket = null
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "onOpen: WS open, response code=${response.code}")
            this@FirmwareWsClient.webSocket = webSocket
            connected.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "onMessage: ${text.take(200)}")
            val doc = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return
            val reqId = doc["reqId"]?.jsonPrimitive?.content
            if (reqId != null && pending.containsKey(reqId)) {
                pending.remove(reqId)?.complete(doc)
            } else {
                eventHandler?.invoke(doc)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "onFailure: ${t::class.simpleName}: ${t.message} — response=$response", t)
            connected.completeExceptionally(t)
            pending.values.forEach { it.completeExceptionally(t) }
            pending.clear()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "onClosed: code=$code reason=$reason")
            pending.values.forEach { it.completeExceptionally(IllegalStateException("closed")) }
            pending.clear()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "onClosing: code=$code reason=$reason")
        }
    }

    companion object {
        private const val TAG = "MyHomeWsClient"
    }
}
