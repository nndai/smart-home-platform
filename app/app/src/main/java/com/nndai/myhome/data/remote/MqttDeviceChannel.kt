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
 * Concrete DeviceChannel implementation for a specific deviceId.
 * Delegates transport connection to MqttConnectionManager and health status tracking to DeviceHandshakeManager.
 */
class MqttDeviceChannel(
    private val connectionManager: MqttConnectionManager,
    private val handshakeManager: DeviceHandshakeManager,
    val deviceId: String,
    private val envelopeProvider: (String) -> String?,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : DeviceChannel {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()

    private var activeJob: Job? = null

    override fun start() {
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
        Log.d(TAG, "MqttDeviceChannel.stop() for deviceId=$deviceId")
        activeJob?.cancel()
        activeJob = null
        _state.value = ConnectionState.Idle
    }

    private val sendMutex = Mutex()

    override suspend fun send(raw: ByteArray): Boolean = sendMutex.withLock {
        if (deviceId.isBlank()) return false

        val isBinary = raw.size >= 3 && raw[0] == 0xB7.toByte() && raw[raw.size - 1] == 0xA5.toByte()
        
        val payloadToSend = if (isBinary) {
            raw
        } else {
            val rawStr = String(raw, Charsets.UTF_8)
            val signedPayloadStr = envelopeProvider(rawStr) ?: run {
                Log.w(TAG, "send() failed: cannot sign command for $deviceId")
                return false
            }
            signedPayloadStr.toByteArray(Charsets.UTF_8)
        }

        val topic = "devices/$deviceId/cmd"
        return connectionManager.publish(topic, payloadToSend)
    }

    companion object {
        private const val TAG = "MqttDeviceChannel"
    }
}
