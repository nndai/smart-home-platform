package com.nndai.myhome.data.remote

import android.util.Log
import com.nndai.myhome.data.model.ConnectionState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Kênh WebSocket để giao tiếp trực tiếp với device qua AP hoặc Debug mode.
 * URL format: ws://{ip}:{port}
 *
 * Connection flow:
 *   Connecting → TransportReady("WebSocket")  [WS opened]
 *              → Connected("WebSocket")       [device handshake ok within 10s]
 *
 * Includes Device Watchdog: if no response is received from device for 10 seconds,
 * demotes to Connecting state and re-probes.
 */
class WebSocketDeviceChannel(
    private val urlProvider: () -> String,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : DeviceChannel {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val incoming: SharedFlow<String> = _incoming.asSharedFlow()

    private var webSocket: WebSocket? = null
    private var connectJob: Job? = null
    private var handshakeJob: Job? = null
    private var watchdogJob: Job? = null

    @Volatile
    private var handshakeComplete = false

    @Volatile
    private var lastDeviceRxTime = 0L

    @Volatile
    private var stopped = false

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "onOpen()")
            this@WebSocketDeviceChannel.webSocket = webSocket
            // Transport is ready — WS connection opened
            _state.value = ConnectionState.TransportReady("WebSocket")
            startHandshake()
            startWatchdog()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.v(TAG, "onMessage payload=${text.take(200)}")
            handleIncoming(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "onClosing code=$code reason=$reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "onClosed code=$code reason=$reason")
            this@WebSocketDeviceChannel.webSocket = null
            cancelHandshake()
            cancelWatchdog()
            handshakeComplete = false
            _state.value = ConnectionState.Connecting
            // Auto-reconnect if not explicitly stopped
            if (!stopped) {
                scheduleReconnect()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "onFailure: ${t.message}", t)
            this@WebSocketDeviceChannel.webSocket = null
            cancelHandshake()
            cancelWatchdog()
            handshakeComplete = false
            _state.value = ConnectionState.Connecting
            // Auto-reconnect if not explicitly stopped
            if (!stopped) {
                scheduleReconnect()
            }
        }
    }

    override fun start() {
        if (urlProvider().isBlank()) {
            Log.e(TAG, "start() invalid URL")
            _state.value = ConnectionState.Failed(IllegalStateException("Missing WebSocket URL"))
            return
        }
        if (webSocket != null || connectJob?.isActive == true) {
            Log.d(TAG, "start() ignored, already connecting/connected")
            return
        }
        stopped = false
        connectJob = scope.launch(dispatcher) {
            connectWithRetry()
        }
    }

    override fun stop() {
        Log.d(TAG, "stop() requested")
        stopped = true
        connectJob?.cancel()
        connectJob = null
        cancelHandshake()
        cancelWatchdog()
        handshakeComplete = false
        webSocket?.close(1000, "stopped")
        webSocket = null
        _state.value = ConnectionState.Disconnected("stopped")
    }

    override suspend fun send(raw: String): Boolean {
        val ws = webSocket ?: return false
        return withContext(dispatcher) {
            runCatching {
                ws.send(raw)
            }.getOrElse {
                Log.e(TAG, "send() failed", it)
                false
            }
        }
    }

    // ── Connection with retry ──

    private suspend fun connectWithRetry() {
        while (!stopped) {
            _state.value = ConnectionState.Connecting
            attemptConnect()
            delay(CONNECT_WAIT_MS)
            if (webSocket != null) {
                return
            }
            if (stopped) return
            Log.d(TAG, "connectWithRetry() retrying in ${RETRY_INTERVAL_MS}ms")
            delay(RETRY_INTERVAL_MS)
        }
    }

    private fun attemptConnect() {
        val baseUrl = urlProvider()
        val url = if (baseUrl.startsWith("ws")) baseUrl else "ws://$baseUrl"
        Log.d(TAG, "attemptConnect() dialing $url")
        _state.value = ConnectionState.Connecting
        val request = Request.Builder().url(url).build()
        httpClient.newWebSocket(request, wsListener)
    }

    private fun scheduleReconnect() {
        if (stopped || connectJob?.isActive == true) return
        Log.d(TAG, "scheduleReconnect() will retry in ${RETRY_INTERVAL_MS}ms")
        _state.value = ConnectionState.Connecting
        connectJob = scope.launch(dispatcher) {
            delay(RETRY_INTERVAL_MS)
            if (!stopped) {
                connectWithRetry()
            }
        }
    }

    // ── Device Watchdog (detects silent/offline device) ──

    private fun startWatchdog() {
        cancelWatchdog()
        watchdogJob = scope.launch(dispatcher) {
            while (isActive && webSocket != null && !stopped) {
                delay(3_000L) // Check every 3 seconds
                val elapsed = System.currentTimeMillis() - lastDeviceRxTime
                if (handshakeComplete && elapsed > WATCHDOG_SILENCE_TIMEOUT_MS) {
                    Log.w(TAG, "Watchdog: No response from device for ${elapsed}ms! Demoting to Connecting state...")
                    handshakeComplete = false
                    _state.value = ConnectionState.Connecting
                    startHandshake()
                }
            }
        }
    }

    private fun cancelWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    // ── Handshake: getStatus probe (10s timeout) ──

    private fun startHandshake() {
        handshakeComplete = false
        cancelHandshake()
        handshakeJob = scope.launch(dispatcher) {
            val startTime = System.currentTimeMillis()
            val timeoutMs = 10_000L

            Log.d(TAG, "startHandshake() sending getStatus probe")
            webSocket?.send(HANDSHAKE_COMMAND)

            while (isActive && webSocket != null && !handshakeComplete) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= timeoutMs) {
                    Log.w(TAG, "startHandshake() 10s timeout reached without getStatus response! Reconnecting...")
                    _state.value = ConnectionState.Connecting
                    webSocket?.close(1000, "handshake timeout")
                    webSocket = null
                    scheduleReconnect()
                    return@launch
                }
                delay(250L)
            }
        }
    }

    private fun cancelHandshake() {
        handshakeJob?.cancel()
        handshakeJob = null
    }

    private fun handleIncoming(payload: String) {
        lastDeviceRxTime = System.currentTimeMillis()

        if (!handshakeComplete) {
            val cmd = runCatching {
                JSONObject(payload).optString("cmd")
            }.getOrNull()
            if (cmd == "getStatus") {
                Log.d(TAG, "handleIncoming() handshake confirmed — device alive via WebSocket")
                handshakeComplete = true
                cancelHandshake()
                _state.value = ConnectionState.Connected("WebSocket")
            }
        }
        if (handshakeComplete) {
            scope.launch(dispatcher) {
                _incoming.emit(payload)
            }
        }
    }

    companion object {
        private const val RETRY_INTERVAL_MS = 10_000L
        private const val CONNECT_WAIT_MS = 12_000L
        private const val WATCHDOG_SILENCE_TIMEOUT_MS = 10_000L
        private const val HANDSHAKE_COMMAND = """{"cmd":"getStatus"}"""
        private const val TAG = "WebSocketDeviceChannel"
    }
}
