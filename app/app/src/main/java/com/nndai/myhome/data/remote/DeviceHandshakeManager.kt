package com.nndai.myhome.data.remote

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Health status of an individual device.
 */
sealed interface DeviceHealthStatus {
    object Unknown : DeviceHealthStatus
    object Handshaking : DeviceHealthStatus
    data class Online(val lastRxTimeMs: Long, val snapshotJson: String? = null) : DeviceHealthStatus
    data class Offline(val lastSeenMs: Long, val reason: String) : DeviceHealthStatus
}

/**
 * Manages per-device handshake and health monitoring independently for each registered device ID.
 * Handshakes use HMAC control keys. Detects per-device data timeouts without affecting global MQTT connections.
 */
class DeviceHandshakeManager(
    context: Context,
    private val connectionManager: MqttConnectionManager,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val envelope = DeviceCommandEnvelope(context.applicationContext)

    // Map of deviceId -> MutableStateFlow<DeviceHealthStatus>
    private val healthStates = ConcurrentHashMap<String, MutableStateFlow<DeviceHealthStatus>>()

    // Map of deviceId -> Watchdog Job
    private val watchdogJobs = ConcurrentHashMap<String, Job>()

    // Map of deviceId -> Last RX timestamp
    private val lastRxTimes = ConcurrentHashMap<String, Long>()

    init {
        // Listen to incoming MQTT messages globally
        scope.launch(dispatcher) {
            connectionManager.incomingMessages.collect { (topic, payload) ->
                handleIncomingMqttMessage(topic, payload)
            }
        }

        // Listen to global MQTT connection status changes
        scope.launch(dispatcher) {
            connectionManager.transportState.collect { transportState ->
                if (transportState is MqttTransportState.Connected) {
                    Log.d(TAG, "MQTT transport connected -> Triggering fast handshake for all tracked devices")
                    triggerHandshakeForAllDevices()
                } else if (transportState is MqttTransportState.Disconnected) {
                    Log.w(TAG, "MQTT transport disconnected -> Marking all devices Offline")
                    markAllDevicesOffline("MQTT transport disconnected")
                }
            }
        }
    }

    /**
     * Registers a device for health tracking and handshake monitoring.
     * Preserves existing health state if device is already registered (e.g. on screen resume).
     */
    fun registerDevice(deviceId: String): StateFlow<DeviceHealthStatus> {
        var isNewRegistration = false
        val stateFlow = healthStates.getOrPut(deviceId) {
            isNewRegistration = true
            MutableStateFlow(DeviceHealthStatus.Handshaking)
        }

        if (isNewRegistration) {
            scope.launch(dispatcher) {
                // Subscribe to device's status and log topics
                connectionManager.subscribe("devices/$deviceId/up")
                connectionManager.subscribe("devices/$deviceId/log")

                // If transport is already connected, initiate handshake
                if (connectionManager.transportState.value is MqttTransportState.Connected) {
                    initiateHandshakeForDevice(deviceId, isInitialProbe = true)
                }
            }
        }

        return stateFlow.asStateFlow()
    }

    /**
     * Unregisters a device from health tracking.
     */
    fun unregisterDevice(deviceId: String) {
        watchdogJobs.remove(deviceId)?.cancel()
        lastRxTimes.remove(deviceId)
        failedAttempts.remove(deviceId)
        healthStates.remove(deviceId)
        scope.launch(dispatcher) {
            connectionManager.unsubscribe("devices/$deviceId/up")
            connectionManager.unsubscribe("devices/$deviceId/log")
        }
    }

    /**
     * Obtains the current health state flow for a registered device.
     */
    fun getHealthStatus(deviceId: String): StateFlow<DeviceHealthStatus>? {
        return healthStates[deviceId]?.asStateFlow()
    }

    // Track consecutive failed handshake attempts per device
    private val failedAttempts = ConcurrentHashMap<String, Int>()

    /**
     * Initiates a fast handshake status request for a specific device using its signed controlKey.
     */
    fun triggerHandshake(deviceId: String) {
        scope.launch(dispatcher) {
            initiateHandshakeForDevice(deviceId, isInitialProbe = true)
        }
    }

    private suspend fun initiateHandshakeForDevice(deviceId: String, isInitialProbe: Boolean = false) {
        val stateFlow = healthStates[deviceId] ?: return
        val attempts = failedAttempts.getOrDefault(deviceId, 0)

        // Show Handshaking (Connecting...) on initial probe or early attempts
        if (isInitialProbe || attempts < 2) {
            stateFlow.value = DeviceHealthStatus.Handshaking
        }
        Log.d(TAG, "initiateHandshakeForDevice(): Sending getStatus to $deviceId (attempt ${attempts + 1})")

        val rawCmd = JSONObject().apply {
            put("cmd", "getStatus")
        }.toString()

        val signedCmd = envelope.sign(deviceId, rawCmd)
        if (signedCmd == null) {
            Log.w(TAG, "initiateHandshakeForDevice(): Cannot sign command for $deviceId (no controlKey)")
            stateFlow.value = DeviceHealthStatus.Offline(System.currentTimeMillis(), "Missing controlKey")
            return
        }

        val topic = "devices/$deviceId/cmd"
        val success = connectionManager.publish(topic, signedCmd)
        if (!success) {
            Log.w(TAG, "initiateHandshakeForDevice(): Failed to publish handshake to $topic")
            handleHandshakeFailure(deviceId)
            return
        }

        startDeviceWatchdog(deviceId)
    }

    private fun handleIncomingMqttMessage(topic: String, payload: String) {
        val deviceId = extractDeviceIdFromTopic(topic) ?: return
        val stateFlow = healthStates[deviceId] ?: return

        // Ignore old retained "announce" messages pushed by broker history during active handshake probe
        if (stateFlow.value is DeviceHealthStatus.Handshaking && payload.contains("\"cmd\":\"announce\"")) {
            Log.d(TAG, "Ignoring retained announce message for $deviceId during handshake probe")
            return
        }

        val now = System.currentTimeMillis()
        lastRxTimes[deviceId] = now
        failedAttempts[deviceId] = 0 // Reset failed attempt counter on successful RX

        stateFlow.value = DeviceHealthStatus.Online(lastRxTimeMs = now, snapshotJson = payload)

        // Reset watchdog timer on successful payload arrival
        startDeviceWatchdog(deviceId)
    }

    private fun startDeviceWatchdog(deviceId: String) {
        watchdogJobs[deviceId]?.cancel()
        watchdogJobs[deviceId] = scope.launch(dispatcher) {
            val stateFlow = healthStates[deviceId]
            val isOnline = stateFlow?.value is DeviceHealthStatus.Online
            val timeoutMs = if (isOnline) ONLINE_HEARTBEAT_TIMEOUT_MS else HANDSHAKE_RESPONSE_TIMEOUT_MS

            delay(timeoutMs)

            val lastRx = lastRxTimes[deviceId] ?: 0L
            val elapsed = System.currentTimeMillis() - lastRx

            if (elapsed >= timeoutMs) {
                Log.w(TAG, "Device watchdog timeout for $deviceId (elapsed=${elapsed}ms). Handling failure...")
                handleHandshakeFailure(deviceId)
            }
        }
    }

    private suspend fun handleHandshakeFailure(deviceId: String) {
        val currentAttempts = (failedAttempts[deviceId] ?: 0) + 1
        failedAttempts[deviceId] = currentAttempts
        val stateFlow = healthStates[deviceId] ?: return
        val lastRx = lastRxTimes[deviceId] ?: 0L

        if (currentAttempts >= MAX_FAILED_HANDSHAKE_ATTEMPTS) {
            // Mark Offline after 2 consecutive failed handshake attempts
            Log.w(TAG, "Device $deviceId marked OFFLINE after $currentAttempts failed handshake attempts.")
            stateFlow.value = DeviceHealthStatus.Offline(lastRx, "$currentAttempts failed handshakes")

            // Continue background probing periodically without UI flickering
            delay(OFFLINE_PROBE_INTERVAL_MS)
            initiateHandshakeForDevice(deviceId)
        } else {
            // Attempt 1 failed -> retry immediately (Attempt 2)
            Log.d(TAG, "Device $deviceId handshake attempt $currentAttempts failed. Retrying immediately...")
            delay(500L)
            initiateHandshakeForDevice(deviceId)
        }
    }

    private fun triggerHandshakeForAllDevices() {
        healthStates.keys.forEach { deviceId ->
            scope.launch(dispatcher) {
                initiateHandshakeForDevice(deviceId)
            }
        }
    }

    private fun markAllDevicesOffline(reason: String) {
        val now = System.currentTimeMillis()
        healthStates.forEach { (deviceId, stateFlow) ->
            watchdogJobs.remove(deviceId)?.cancel()
            stateFlow.value = DeviceHealthStatus.Offline(now, reason)
        }
    }

    private fun extractDeviceIdFromTopic(topic: String): String? {
        // Topic format: devices/{deviceId}/up or devices/{deviceId}/log
        val parts = topic.split("/")
        if (parts.size >= 3 && parts[0] == "devices") {
            return parts[1]
        }
        return null
    }

    companion object {
        private const val TAG = "DeviceHandshakeMgr"
        private const val MAX_FAILED_HANDSHAKE_ATTEMPTS = 2
        private const val HANDSHAKE_RESPONSE_TIMEOUT_MS = 4_000L
        private const val ONLINE_HEARTBEAT_TIMEOUT_MS = 15_000L
        private const val OFFLINE_PROBE_INTERVAL_MS = 10_000Lb 
    }
}
