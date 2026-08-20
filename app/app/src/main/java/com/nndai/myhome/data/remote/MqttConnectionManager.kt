package com.nndai.myhome.data.remote

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID

/**
 * Represents the transport connection status of the global MQTT broker.
 */
sealed interface MqttTransportState {
    object Idle : MqttTransportState
    object Connecting : MqttTransportState
    data class Connected(val brokerUri: String) : MqttTransportState
    data class Disconnected(val cause: String?) : MqttTransportState
    data class Failed(val error: Throwable) : MqttTransportState
}

/**
 * High-level manager responsible ONLY for establishing and maintaining the global MQTT socket connection.
 * Does NOT contain any per-device handshake, telemetry streaming, or watchdog logic.
 */
class MqttConnectionManager(
    private val hostProvider: () -> String,
    private val portProvider: () -> Int,
    private val usernameProvider: () -> String,
    private val passwordProvider: () -> String,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val _transportState = MutableStateFlow<MqttTransportState>(MqttTransportState.Idle)
    val transportState: StateFlow<MqttTransportState> = _transportState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<Pair<String, String>>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val incomingMessages: SharedFlow<Pair<String, String>> = _incomingMessages.asSharedFlow()

    private var client: MqttClient? = null
    private var connectJob: Job? = null
    private val subscribedTopics = mutableSetOf<String>()

    @Volatile
    private var isExplicitlyStopped = false

    private val callback = object : MqttCallbackExtended {
        override fun connectComplete(reconnect: Boolean, serverURI: String?) {
            Log.d(TAG, "MQTT connectComplete: reconnect=$reconnect uri=$serverURI")
            scope.launch(dispatcher) {
                resubscribeAllTopics()
                _transportState.value = MqttTransportState.Connected(serverURI ?: "")
            }
        }

        override fun connectionLost(cause: Throwable?) {
            Log.w(TAG, "MQTT connectionLost: ${cause?.message}", cause)
            client = null
            _transportState.value = MqttTransportState.Disconnected(cause?.message)
            if (!isExplicitlyStopped) {
                scheduleAutoReconnect()
            }
        }

        override fun messageArrived(topic: String?, message: MqttMessage?) {
            if (topic == null || message == null) return
            val payload = message.payload?.toString(Charsets.UTF_8) ?: return
            _incomingMessages.tryEmit(Pair(topic, payload))
        }

        override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
    }

    /**
     * Starts establishing the global MQTT connection asynchronously.
     */
    fun connect() {
        if (client?.isConnected == true || connectJob?.isActive == true) {
            Log.d(TAG, "connect() skipped: already connected or connecting")
            return
        }
        isExplicitlyStopped = false
        Log.d(TAG, "connect() initiating dial to ${hostProvider()}:${portProvider()}")
        connectJob = scope.launch(dispatcher) {
            runConnectionLoop()
        }
    }

    /**
     * Disconnects the MQTT client and cancels auto-reconnects.
     */
    fun disconnect() {
        Log.d(TAG, "disconnect() requested")
        isExplicitlyStopped = true
        connectJob?.cancel()
        connectJob = null
        scope.launch(dispatcher) {
            closeSocketInternal("User disconnect")
        }
    }

    /**
     * Subscribes to a specific MQTT topic filter with QoS 1.
     */
    suspend fun subscribe(topic: String) {
        withContext(dispatcher) {
            subscribedTopics.add(topic)
            client?.let {
                if (it.isConnected) {
                    runCatching { it.subscribe(topic, 1) }
                        .onSuccess { Log.d(TAG, "Subscribed to topic: $topic") }
                        .onFailure { e -> Log.e(TAG, "Failed to subscribe to $topic: ${e.message}") }
                }
            }
        }
    }

    /**
     * Unsubscribes from an MQTT topic filter.
     */
    suspend fun unsubscribe(topic: String) {
        withContext(dispatcher) {
            subscribedTopics.remove(topic)
            client?.let {
                if (it.isConnected) {
                    runCatching { it.unsubscribe(topic) }
                }
            }
        }
    }

    /**
     * Publishes a string payload to the target topic with QoS 1.
     */
    suspend fun publish(topic: String, payload: String): Boolean {
        val currentClient = client ?: run {
            Log.w(TAG, "publish() failed: client is null")
            return false
        }
        if (!currentClient.isConnected) {
            Log.w(TAG, "publish() failed: client not connected")
            return false
        }
        return withContext(dispatcher) {
            runCatching {
                val bytes = payload.toByteArray(Charsets.UTF_8)
                currentClient.publish(topic, bytes, 1, false)
                Log.d(TAG, "publish() OK to $topic payload=${payload.take(150)}")
                true
            }.getOrElse { e ->
                Log.e(TAG, "publish() failed to $topic: ${e.message}")
                false
            }
        }
    }

    private suspend fun runConnectionLoop() {
        while (!isExplicitlyStopped) {
            _transportState.value = MqttTransportState.Connecting
            val success = attemptSingleConnect()
            if (success) {
                return
            }
            if (isExplicitlyStopped) return
            Log.d(TAG, "Connection loop retrying in ${RETRY_INTERVAL_MS}ms")
            delay(RETRY_INTERVAL_MS)
        }
    }

    private suspend fun attemptSingleConnect(): Boolean {
        return try {
            val host = hostProvider()
            val port = portProvider()
            val useTls = port != 1883
            val uri = if (useTls) "ssl://$host:$port" else "tcp://$host:$port"

            Log.d(TAG, "attemptSingleConnect() connecting to $uri")
            val mqttClient = MqttClient(uri, buildClientId(), MemoryPersistence()).apply {
                setCallback(callback)
            }
            client = mqttClient
            mqttClient.connect(buildOptions())
            resubscribeAllTopics()
            Log.d(TAG, "attemptSingleConnect() SUCCESS. Broker connected.")
            _transportState.value = MqttTransportState.Connected(uri)
            true
        } catch (e: Exception) {
            Log.e(TAG, "attemptSingleConnect() failed: ${e.message}")
            closeSocketInternal(e.message, updateState = false)
            _transportState.value = MqttTransportState.Failed(e)
            false
        } finally {
            connectJob = null
            if (client?.isConnected != true) {
                client = null
            }
        }
    }

    private fun scheduleAutoReconnect() {
        if (isExplicitlyStopped || connectJob?.isActive == true) return
        Log.d(TAG, "scheduleAutoReconnect() scheduling retry in ${RETRY_INTERVAL_MS}ms")
        _transportState.value = MqttTransportState.Connecting
        connectJob = scope.launch(dispatcher) {
            delay(RETRY_INTERVAL_MS)
            if (!isExplicitlyStopped) {
                runConnectionLoop()
            }
        }
    }

    private fun resubscribeAllTopics() {
        val currentClient = client ?: return
        if (!currentClient.isConnected) return
        subscribedTopics.forEach { topic ->
            runCatching { currentClient.subscribe(topic, 1) }
        }
    }

    private fun closeSocketInternal(reason: String?, updateState: Boolean = true) {
        runCatching {
            client?.apply {
                if (isConnected) disconnectForcibly(1000)
                close()
            }
        }
        client = null
        if (updateState) {
            _transportState.value = MqttTransportState.Disconnected(reason)
        }
    }

    private fun buildClientId(): String {
        val uuidShort = UUID.randomUUID().toString().take(8)
        return "myhome-app-$uuidShort"
    }

    private fun buildOptions(): MqttConnectOptions {
        return MqttConnectOptions().apply {
            isCleanSession = true
            connectionTimeout = 10
            keepAliveInterval = 30
            isAutomaticReconnect = true
            maxInflight = 100
            val user = usernameProvider()
            val pass = passwordProvider()
            if (user.isNotBlank()) {
                userName = user
                password = pass.toCharArray()
            }
        }
    }

    companion object {
        private const val TAG = "MqttConnectionMgr"
        private const val RETRY_INTERVAL_MS = 3_000L
    }
}
