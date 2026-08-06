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
import org.json.JSONObject
import java.util.UUID
import javax.net.ssl.SSLSocketFactory
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/**
 * Kênh MQTT TLS để giao tiếp với RemotePump LN882H.
 *
 * Connection flow:
 *   Connecting → TransportReady("MQTT")  [broker connected]
 *              → Connected("MQTT")       [device handshake ok within 10s]
 *
 * Includes Device Watchdog: if no response is received from device for 10 seconds,
 * demotes to Connecting state and re-probes.
 */
class MqttDeviceChannel(
    private val hostProvider: () -> String,
    private val portProvider: () -> Int,
    private val usernameProvider: () -> String,
    private val passwordProvider: () -> String,
    private val baseTopicProvider: () -> String,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : DeviceChannel {

    private val topicCmd get() = "${baseTopicProvider()}/cmd"
    private val topicSubscribe get() = baseTopicProvider()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val incoming: SharedFlow<String> = _incoming.asSharedFlow()

    private var client: MqttClient? = null
    private var connectJob: Job? = null
    private var handshakeJob: Job? = null
    private var watchdogJob: Job? = null

    @Volatile
    private var handshakeComplete = false

    @Volatile
    private var lastDeviceRxTime = 0L

    @Volatile
    private var stopped = false

    private val callback = object : MqttCallbackExtended {
        override fun connectComplete(reconnect: Boolean, serverURI: String?) {
            Log.d(TAG, "connectComplete() reconnect=$reconnect uri=$serverURI")
            scope.launch(dispatcher) {
                runCatching { client?.subscribe(topicSubscribe, 1) }
                // Transport is ready — broker connected, topic subscribed
                _state.value = ConnectionState.TransportReady("MQTT")
                startHandshake()
                startWatchdog()
            }
        }

        override fun connectionLost(cause: Throwable?) {
            Log.w(TAG, "connectionLost: ${cause?.message}", cause)
            client = null
            cancelHandshake()
            cancelWatchdog()
            handshakeComplete = false
            _state.value = ConnectionState.Connecting
            // Auto-reconnect if not explicitly stopped
            if (!stopped) {
                scheduleReconnect()
            }
        }

        override fun messageArrived(topic: String?, message: MqttMessage?) {
            val payload = message?.payload?.toString(Charsets.UTF_8) ?: return
            Log.v(TAG, "messageArrived payload=${payload.take(200)}")
            handleIncoming(payload)
        }

        override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
    }

    override fun start() {
        if (!isConfigValid()) {
            Log.e(TAG, "start() invalid config")
            _state.value = ConnectionState.Failed(IllegalStateException("Missing MQTT config"))
            return
        }
        if (client?.isConnected == true || connectJob?.isActive == true) {
            Log.d(TAG, "start() ignored, already connecting/connected")
            return
        }
        stopped = false
        Log.d(TAG, "start() connecting to ${hostProvider()}:${portProvider()} topic=${baseTopicProvider()}")
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
        scope.launch(dispatcher) {
            disconnectInternal("stopped")
        }
    }

    override suspend fun send(raw: String): Boolean {
        val current = client ?: return false
        if (!current.isConnected) return false
        return withContext(dispatcher) {
            runCatching {
                val payload = raw.toByteArray(Charsets.UTF_8)
                current.publish(topicCmd, payload, 1, false)
                Log.d(TAG, "send() publish ok to $topicCmd payload=${raw.take(200)}")
                true
            }.getOrElse {
                Log.e(TAG, "send() publish failed", it)
                false
            }
        }
    }

    // ── Connection with retry ──

    private suspend fun connectWithRetry() {
        while (!stopped) {
            _state.value = ConnectionState.Connecting
            val success = attemptConnect()
            if (success) {
                // Connected to broker — callback will handle TransportReady + handshake
                return
            }
            if (stopped) return
            Log.d(TAG, "connectWithRetry() retrying in ${RETRY_INTERVAL_MS}ms")
            delay(RETRY_INTERVAL_MS)
        }
    }

    private suspend fun attemptConnect(): Boolean {
        return try {
            val host = hostProvider()
            val port = portProvider()
            val useTls = port != 1883
            val uri = if (useTls) "ssl://$host:$port" else "tcp://$host:$port"
            Log.d(TAG, "attemptConnect() dialing $uri")
            val mqttClient = MqttClient(uri, buildClientId(), MemoryPersistence()).apply {
                setCallback(callback)
            }
            client = mqttClient
            val options = buildOptions()
            mqttClient.connect(options)
            mqttClient.subscribe(topicSubscribe, 1)
            Log.d(TAG, "attemptConnect() broker connected, topic subscribed")
            _state.value = ConnectionState.TransportReady("MQTT")
//            startHandshake()
//            startWatchdog()
            true
        } catch (ex: Exception) {
            Log.e(TAG, "attemptConnect() failed: ${ex.message}")
            disconnectInternal(ex.message, emitState = false)
            _state.value = ConnectionState.Connecting
            false
        } finally {
            connectJob = null
            if (client?.isConnected != true) {
                client = null
            }
        }
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

    // ── Disconnect ──

    private suspend fun disconnectInternal(reason: String?, emitState: Boolean = true) {
        val current = client
        if (current != null) {
            runCatching { current.unsubscribe(topicSubscribe) }
            runCatching { current.disconnectForcibly(1000, 1000) }
            runCatching { current.close() }
        }
        client = null
        cancelHandshake()
        cancelWatchdog()
        handshakeComplete = false
        if (emitState) {
            Log.d(TAG, "disconnectInternal() reason=$reason")
            _state.value = ConnectionState.Disconnected(reason)
        }
    }

    // ── Device Watchdog (detects silent/offline device) ──

    private fun startWatchdog() {
        cancelWatchdog()
        watchdogJob = scope.launch(dispatcher) {
            while (isActive && client?.isConnected == true && !stopped) {
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
            publishInternal(HANDSHAKE_COMMAND)

            while (isActive && client?.isConnected == true && !handshakeComplete) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= timeoutMs) {
                    Log.w(TAG, "startHandshake() 10s timeout reached without getStatus response! Retrying handshake...")
                    _state.value = ConnectionState.Connecting
                    startHandshake()
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

        // Check handshake: any response with cmd=getStatus confirms device is alive
        if (!handshakeComplete) {
            val cmd = runCatching {
                JSONObject(payload).optString("cmd")
            }.getOrNull()
            if (cmd == "getStatus") {
                Log.d(TAG, "handleIncoming() handshake confirmed — device alive via MQTT")
                handshakeComplete = true
                cancelHandshake()
                _state.value = ConnectionState.Connected("MQTT")
            }
        }
        if (handshakeComplete) {
            scope.launch(dispatcher) {
                _incoming.emit(payload)
            }
        }
    }

    // ── Helpers ──

    private fun buildOptions(): MqttConnectOptions {
        return MqttConnectOptions().apply {
            isCleanSession = true
            connectionTimeout = 15
            keepAliveInterval = 30
            val un = usernameProvider()
            val pw = passwordProvider()
            if (un.isNotBlank()) {
                userName = un
            }
            if (pw.isNotBlank()) {
                password = pw.toCharArray()
            }
            if (portProvider() != 1883) {
                socketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            }
        }
    }

    private fun buildClientId(): String {
        return "remotepump-app-${UUID.randomUUID().toString().take(8)}"
    }

    private fun isConfigValid(): Boolean {
        return hostProvider().isNotBlank() && baseTopicProvider().isNotBlank() && portProvider() > 0
    }

    private suspend fun publishInternal(raw: String): Boolean {
        val current = client ?: return false
        return runCatching {
            current.publish(topicCmd, raw.toByteArray(Charsets.UTF_8), 1, false)
            true
        }.getOrElse {
            Log.e(TAG, "publishInternal() failed", it)
            false
        }
    }

    companion object {
        private const val RETRY_INTERVAL_MS = 10_000L
        private const val WATCHDOG_SILENCE_TIMEOUT_MS = 10_000L
        private const val HANDSHAKE_COMMAND = """{"cmd":"getStatus"}"""
        private const val TAG = "MqttDeviceChannel"
    }
}
