package com.nndai.myhome.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Features automated socket recovery, exponential backoff reconnects, and Android network change listeners.
 */
class MqttConnectionManager(
    context: Context? = null,
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

    @Volatile
    private var client: MqttClient? = null
    private var connectJob: Job? = null
    private val connectMutex = Mutex()
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

    init {
        // Register Android system network callback for immediate reconnect upon network availability
        context?.applicationContext?.let { ctx ->
            runCatching {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm?.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        Log.d(TAG, "Android Network became available -> Triggering fast MQTT reconnect")
                        if (!isExplicitlyStopped && client?.isConnected != true) {
                            connect()
                        }
                    }

                    override fun onLost(network: Network) {
                        Log.w(TAG, "Android Network lost!")
                        _transportState.value = MqttTransportState.Disconnected("Network lost")
                    }
                })
            }.onFailure { e ->
                Log.w(TAG, "Failed to register network callback: ${e.message}")
            }
        }
    }

    /**
     * Starts establishing the global MQTT connection asynchronously.
     */
    fun connect() {
        if (client?.isConnected == true) {
            Log.d(TAG, "connect() skipped: already connected")
            return
        }
        isExplicitlyStopped = false
        if (connectJob?.isActive == true) {
            Log.d(TAG, "connect() skipped: connection loop already active")
            return
        }
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
        connectMutex.withLock {
            var currentBackoffMs = INITIAL_RETRY_INTERVAL_MS
            while (!isExplicitlyStopped) {
                if (client?.isConnected == true) {
                    _transportState.value = MqttTransportState.Connected(client?.serverURI ?: "")
                    return
                }

                _transportState.value = MqttTransportState.Connecting
                val success = attemptSingleConnect()
                if (success) {
                    return
                }
                if (isExplicitlyStopped) return

                Log.d(TAG, "Connection loop retrying in ${currentBackoffMs}ms")
                delay(currentBackoffMs)
                currentBackoffMs = (currentBackoffMs * 1.5).toLong().coerceAtMost(MAX_RETRY_INTERVAL_MS)
            }
        }
    }

    private suspend fun attemptSingleConnect(): Boolean {
        return try {
            val host = hostProvider()
            val port = portProvider()
            val useTls = port != 1883
            val uri = if (useTls) "ssl://$host:$port" else "tcp://$host:$port"

            Log.d(TAG, "attemptSingleConnect() connecting to $uri")

            // Close any existing client safely before establishing a new one
            runCatching {
                client?.apply {
                    if (isConnected) disconnectForcibly(500)
                    close()
                }
            }

            val mqttClient = MqttClient(uri, buildClientId(), MemoryPersistence()).apply {
                setCallback(callback)
            }
            mqttClient.connect(buildOptions())
            client = mqttClient

            resubscribeAllTopics()
            Log.d(TAG, "attemptSingleConnect() SUCCESS. Broker connected.")
            _transportState.value = MqttTransportState.Connected(uri)
            true
        } catch (e: Exception) {
            Log.e(TAG, "attemptSingleConnect() failed: ${e.message}")
            closeSocketInternal(e.message, updateState = false)
            _transportState.value = MqttTransportState.Failed(e)
            false
        }
    }

    private fun scheduleAutoReconnect() {
        if (isExplicitlyStopped || connectJob?.isActive == true) return
        Log.d(TAG, "scheduleAutoReconnect() scheduling retry in ${INITIAL_RETRY_INTERVAL_MS}ms")
        _transportState.value = MqttTransportState.Connecting
        connectJob = scope.launch(dispatcher) {
            delay(INITIAL_RETRY_INTERVAL_MS)
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
            isAutomaticReconnect = false // Controlled via Kotlin coroutine backoff loop
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
        private const val INITIAL_RETRY_INTERVAL_MS = 2_000L
        private const val MAX_RETRY_INTERVAL_MS = 15_000L
    }
}
