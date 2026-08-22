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
 * Features seamless background heartbeat probes that do NOT cause UI state flickering when devices are idle.
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

    // Track consecutive failed handshake attempts per device
    private val failedAttempts = ConcurrentHashMap<String, Int>()

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
        val isExisting = healthStates.containsKey(deviceId)
        val stateFlow = healthStates.getOrPut(deviceId) {
            MutableStateFlow(DeviceHealthStatus.Handshaking)
        }

        scope.launch(dispatcher) {
            // Subscribe to device's status and log topics
            connectionManager.subscribe("devices/$deviceId/up")
            connectionManager.subscribe("devices/$deviceId/log")

            // If transport is already connected, initiate handshake probe
            if (connectionManager.transportState.value is MqttTransportState.Connected) {
                if (!isExisting) {
                    initiateHandshakeForDevice(deviceId, isSilentProbe = false, isInitialProbe = true)
                } else {
                    val currentState = stateFlow.value
                    if (currentState is DeviceHealthStatus.Unknown) {
                        initiateHandshakeForDevice(deviceId, isSilentProbe = false, isInitialProbe = true)
                    }
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

    /**
     * Initiates a fast handshake status request for a specific device using its signed controlKey.
     */
    fun triggerHandshake(deviceId: String) {
        scope.launch(dispatcher) {
            initiateHandshakeForDevice(deviceId, isSilentProbe = false, isInitialProbe = true)
        }
    }

    /**
     * Sends a getStatus probe to the device.
     * @param isSilentProbe If true, does NOT change state to Handshaking (Connecting...) on UI.
     * @param isInitialProbe If true, forces state to Handshaking (Connecting...) for new screens/connections.
     */
    private suspend fun initiateHandshakeForDevice(
        deviceId: String,
        isSilentProbe: Boolean = false,
        isInitialProbe: Boolean = false
    ) {
        val stateFlow = healthStates[deviceId] ?: return
        val attempts = failedAttempts.getOrDefault(deviceId, 0)

        // Only update UI to Handshaking if it's an initial probe OR if silent probe has already failed
        if (isInitialProbe || (!isSilentProbe && attempts >= 1)) {
            stateFlow.value = DeviceHealthStatus.Handshaking
        }

        Log.d(
            TAG,
            "initiateHandshakeForDevice(): Sending getStatus to $deviceId (attempt ${attempts + 1}, silent=$isSilentProbe)"
        )

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

        // Start response timeout watchdog for this probe
        startProbeResponseWatchdog(deviceId, isSilentProbe)
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

        // Schedule next idle check watchdog
        scheduleIdleWatchdog(deviceId)
    }

    /**
     * Schedules an idle watchdog when device is Online.
     * When device has been idle for ONLINE_IDLE_CHECK_INTERVAL_MS (30s), sends a silent check probe.
     */
    private fun scheduleIdleWatchdog(deviceId: String) {
        watchdogJobs[deviceId]?.cancel()
        watchdogJobs[deviceId] = scope.launch(dispatcher) {
            delay(ONLINE_IDLE_CHECK_INTERVAL_MS)

            val lastRx = lastRxTimes[deviceId] ?: 0L
            val elapsed = System.currentTimeMillis() - lastRx

            if (elapsed >= ONLINE_IDLE_CHECK_INTERVAL_MS) {
                Log.d(TAG, "Device $deviceId idle for ${elapsed}ms -> Sending Silent Probe 1 (keeping Online state)")
                initiateHandshakeForDevice(deviceId, isSilentProbe = true, isInitialProbe = false)
            }
        }
    }

    /**
     * Watches for response after sending a probe.
     */
    private fun startProbeResponseWatchdog(deviceId: String, isSilentProbe: Boolean) {
        watchdogJobs[deviceId]?.cancel()
        watchdogJobs[deviceId] = scope.launch(dispatcher) {
            delay(PROBE_RESPONSE_TIMEOUT_MS)

            val lastRx = lastRxTimes[deviceId] ?: 0L
            val elapsed = System.currentTimeMillis() - lastRx

            // If no message arrived within the response timeout window
            if (elapsed >= PROBE_RESPONSE_TIMEOUT_MS) {
                Log.w(TAG, "Probe response timeout for $deviceId (silent=$isSilentProbe, elapsed=${elapsed}ms)")
                handleHandshakeFailure(deviceId)
            }
        }
    }

    private suspend fun handleHandshakeFailure(deviceId: String) {
        val currentAttempts = (failedAttempts[deviceId] ?: 0) + 1
        failedAttempts[deviceId] = currentAttempts
        val stateFlow = healthStates[deviceId] ?: return
        val lastRx = lastRxTimes[deviceId] ?: 0L

        when {
            currentAttempts == 1 -> {
                // Check 1 (Silent Probe) failed -> Now transition UI to Handshaking (Connecting...) and send Check 2 (Retry)
                Log.d(TAG, "Device $deviceId check 1 failed -> Transitioning to Connecting... and sending Check 2")
                stateFlow.value = DeviceHealthStatus.Handshaking
                delay(300L)
                initiateHandshakeForDevice(deviceId, isSilentProbe = false, isInitialProbe = false)
            }
            currentAttempts >= MAX_FAILED_HANDSHAKE_ATTEMPTS -> {
                // Check 2 failed -> Mark Offline
                Log.w(TAG, "Device $deviceId marked OFFLINE after $currentAttempts failed handshake attempts.")
                stateFlow.value = DeviceHealthStatus.Offline(lastRx, "$currentAttempts failed handshakes")

                // Schedule background retry probe periodically
                delay(OFFLINE_PROBE_INTERVAL_MS)
                initiateHandshakeForDevice(deviceId, isSilentProbe = true, isInitialProbe = false)
            }
            else -> {
                delay(1000L)
                initiateHandshakeForDevice(deviceId, isSilentProbe = false, isInitialProbe = false)
            }
        }
    }

    private fun triggerHandshakeForAllDevices() {
        healthStates.keys.forEach { deviceId ->
            scope.launch(dispatcher) {
                initiateHandshakeForDevice(deviceId, isSilentProbe = false, isInitialProbe = true)
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
        private const val MAX_FAILED_HANDSHAKE_ATTEMPTS = 3
        private const val ONLINE_IDLE_CHECK_INTERVAL_MS = 30_000L // 30 seconds idle check
        private const val PROBE_RESPONSE_TIMEOUT_MS = 5_000L     // 5 seconds wait for probe reply
        private const val OFFLINE_PROBE_INTERVAL_MS = 15_000L     // 15 seconds retry while offline
    }
}
