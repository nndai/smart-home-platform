package com.nndai.myhome.data.remote

import android.util.Log
import com.nndai.myhome.data.model.ConnectionState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Concrete DeviceChannel implementation.
 * Delegates transport connection to MqttConnectionManager and health status tracking to DeviceHandshakeManager.
 */
class MqttDeviceChannel(
    private val connectionManager: MqttConnectionManager,
    private val handshakeManager: DeviceHandshakeManager,
    private val deviceIdProvider: () -> String,
    private val envelopeProvider: (String) -> String?,
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

    private var activeJob: Job? = null

    override fun start() {
        val deviceId = deviceIdProvider()
        if (deviceId.isBlank()) {
            _state.value = ConnectionState.Failed(IllegalStateException("Empty deviceId"))
            return
        }

        Log.d(TAG, "MqttDeviceChannel.start() for deviceId=$deviceId")
        activeJob?.cancel()

        activeJob = scope.launch(dispatcher) {
            // Register device with handshake manager for health tracking
            val healthFlow = handshakeManager.registerDevice(deviceId)

            // Collect incoming messages targeting this specific device
            launch {
                connectionManager.incomingMessages.collect { (topic, payload) ->
                    if (topic.startsWith("devices/$deviceId/")) {
                        _incoming.tryEmit(payload)
                    }
                }
            }

            // Sync connection state with per-device health status
            launch {
                healthFlow.collect { health ->
                    _state.value = when (health) {
                        is DeviceHealthStatus.Online -> ConnectionState.Connected("MQTT")
                        is DeviceHealthStatus.Handshaking -> ConnectionState.Connecting
                        is DeviceHealthStatus.Offline -> ConnectionState.Connecting
                        is DeviceHealthStatus.Unknown -> ConnectionState.Connecting
                    }
                }
            }

            // Trigger handshake probe
            handshakeManager.triggerHandshake(deviceId)
        }
    }

    override fun stop() {
        Log.d(TAG, "MqttDeviceChannel.stop()")
        activeJob?.cancel()
        activeJob = null
        _state.value = ConnectionState.Idle
    }

    private val sendMutex = Mutex()

    override suspend fun send(raw: String): Boolean = sendMutex.withLock {
        val deviceId = deviceIdProvider()
        if (deviceId.isBlank()) return false

        val signedPayload = envelopeProvider(raw) ?: run {
            Log.w(TAG, "send() failed: cannot sign command for $deviceId")
            return false
        }

        val topic = "devices/$deviceId/cmd"
        return connectionManager.publish(topic, signedPayload)
    }

    companion object {
        private const val TAG = "MqttDeviceChannel"
    }
}
