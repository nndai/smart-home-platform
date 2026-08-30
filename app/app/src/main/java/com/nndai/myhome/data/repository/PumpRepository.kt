package com.nndai.myhome.data.repository

import android.util.Log
import com.nndai.myhome.data.model.ConnectionState
import com.nndai.myhome.data.model.DeviceConfig
import com.nndai.myhome.data.model.DeviceInfo
import com.nndai.myhome.data.model.PumpStatus
import com.nndai.myhome.data.remote.DeviceChannel
import com.nndai.myhome.data.remote.PumpCommandDataSource
import com.nndai.myhome.data.remote.PumpCommandEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Single source of truth cho dữ liệu thiết bị.
 * Quản lý vòng đời stream status & system info theo chu kỳ 1 phút, chỉ stream khi người dùng ở trong màn hình/tab tương ứng.
 */
class PumpRepository(
    private val remote: PumpCommandDataSource,
    private val channel: DeviceChannel,
    private val scope: CoroutineScope
) {

    private val _pumpStatus = MutableStateFlow<PumpStatus?>(null)
    val pumpStatus: StateFlow<PumpStatus?> = _pumpStatus.asStateFlow()

    private var lastStatusReceivedTime: Long = 0L
    private val _statusLatencyMs = MutableStateFlow<Long?>(null)
    val statusLatencyMs: StateFlow<Long?> = _statusLatencyMs.asStateFlow()

    private val _isStatusStale = MutableStateFlow(false)
    val isStatusStale: StateFlow<Boolean> = _isStatusStale.asStateFlow()

    private val _deviceConfig = MutableStateFlow<DeviceConfig?>(null)
    val deviceConfig: StateFlow<DeviceConfig?> = _deviceConfig.asStateFlow()

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()

    private val _logs = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    private val _isLogEnabled = MutableStateFlow(false)
    val isLogEnabled: StateFlow<Boolean> = _isLogEnabled.asStateFlow()

    private val _commandEvents = MutableSharedFlow<PumpCommandEvent.CommandResult>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val commandEvents: SharedFlow<PumpCommandEvent.CommandResult> = _commandEvents.asSharedFlow()

    private val _wifiScanResults = MutableSharedFlow<PumpCommandEvent.WifiScanResult>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val wifiScanResults: SharedFlow<PumpCommandEvent.WifiScanResult> = _wifiScanResults.asSharedFlow()

    private val _wifiScanCompleted = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val wifiScanCompleted: SharedFlow<Unit> = _wifiScanCompleted.asSharedFlow()

    val connectionState: StateFlow<ConnectionState> = channel.state

    // ── Stream Lifecycle Tracking ──
    private var statusStreamJob: Job? = null
    private var lastStatusStreamTimeMs: Long = 0L

    private var sysInfoStreamJob: Job? = null
    private var lastSysInfoStreamTimeMs: Long = 0L

    init {
        Log.d(TAG, "init: start device channel")
        channel.start()

        // Collect remote events → update state flows
        scope.launch {
            remote.events.collect { event ->
                when (event) {
                    is PumpCommandEvent.StatusUpdate -> {
                        val now = System.currentTimeMillis()
                        if (lastStatusReceivedTime > 0L) {
                            _statusLatencyMs.value = now - lastStatusReceivedTime
                        }
                        lastStatusReceivedTime = now
                        _isStatusStale.value = false
                        _pumpStatus.value = event.status
                    }
                    is PumpCommandEvent.ConfigUpdate -> _deviceConfig.value = event.config
                    is PumpCommandEvent.InfoUpdate -> _deviceInfo.value = event.info
                    is PumpCommandEvent.CommandResult -> {
                        _commandEvents.emit(event)
                        if (event.command == "setRelay" && event.success) {
                            val isRelayOn = event.message == "on"
                            _pumpStatus.value = _pumpStatus.value?.copy(relay = isRelayOn)
                        }
                    }
                    is PumpCommandEvent.WifiScanCompleted -> _wifiScanCompleted.emit(Unit)
                    is PumpCommandEvent.WifiScanResult -> _wifiScanResults.emit(event)
                    is PumpCommandEvent.Failure -> _commandEvents.emit(
                        PumpCommandEvent.CommandResult("error", false, event.message)
                    )
                    is PumpCommandEvent.LogMessage -> _logs.emit(event.message)
                    is PumpCommandEvent.LogMqttStatus -> _isLogEnabled.value = event.enabled
                    else -> {}
                }
            }
        }

        // Ticker kiểm tra độ trễ nhận getStatus (mỗi 1s):
        // Nếu quá 3s (>3000ms) không nhận được getStatus thì cập nhật ms tăng dần và bật cờ isStatusStale
        scope.launch {
            while (isActive) {
                delay(1000L)
                if (channel.state.value is ConnectionState.Connected && lastStatusReceivedTime > 0L) {
                    val elapsed = System.currentTimeMillis() - lastStatusReceivedTime
                    if (elapsed > 3000L) {
                        _statusLatencyMs.value = elapsed
                        _isStatusStale.value = true
                    }
                }
            }
        }

        // When transport reconnects, renew active stream subscriptions if any
        scope.launch {
            connectionState.collectLatest { state ->
                Log.d(TAG, "connection state=$state")
                if (state is ConnectionState.Connected) {
                    if (statusStreamJob?.isActive == true) {
                        ensureStatusStream()
                    }
                    if (sysInfoStreamJob?.isActive == true) {
                        ensureSysInfoStream()
                    }
                } else {
                    lastStatusReceivedTime = 0L
                    _statusLatencyMs.value = null
                    _isStatusStale.value = false
                }
            }
        }
    }

    // ── Stream Control API ──

    /**
     * Ensures status stream is active.
     * If more than 60s has passed since the last stream request, sends getStatus(stream=true).
     * Maintains a recurring 60s loop while active in device view.
     */
    fun ensureStatusStream() {
        val now = System.currentTimeMillis()
        if (now - lastStatusStreamTimeMs >= STREAM_RENEWAL_INTERVAL_MS) {
            lastStatusStreamTimeMs = now
            scope.launch {
                Log.d(TAG, "ensureStatusStream: Sending getStatus(stream=true)")
                remote.getStatus(stream = true)
            }
        }
        if (statusStreamJob?.isActive != true) {
            statusStreamJob = scope.launch {
                while (isActive) {
                    delay(STREAM_RENEWAL_INTERVAL_MS)
                    lastStatusStreamTimeMs = System.currentTimeMillis()
                    Log.d(TAG, "statusStreamLoop: Periodic 1min renewal getStatus(stream=true)")
                    remote.getStatus(stream = true)
                }
            }
        }
    }

    /**
     * Stops the repeating status stream loop.
     */
    fun stopStatusStream() {
        Log.d(TAG, "stopStatusStream()")
        statusStreamJob?.cancel()
        statusStreamJob = null
    }

    /**
     * Ensures system info stream is active (called ONLY when user is on System Info tab).
     * If more than 60s has passed since the last sys info stream request, sends getInfo(stream=true).
     * Maintains a recurring 60s loop while in System tab.
     */
    fun ensureSysInfoStream() {
        val now = System.currentTimeMillis()
        if (now - lastSysInfoStreamTimeMs >= STREAM_RENEWAL_INTERVAL_MS) {
            lastSysInfoStreamTimeMs = now
            scope.launch {
                Log.d(TAG, "ensureSysInfoStream: Sending getInfo(stream=true)")
                remote.getInfo(stream = true)
            }
        }
        if (sysInfoStreamJob?.isActive != true) {
            sysInfoStreamJob = scope.launch {
                while (isActive) {
                    delay(STREAM_RENEWAL_INTERVAL_MS)
                    lastSysInfoStreamTimeMs = System.currentTimeMillis()
                    Log.d(TAG, "sysInfoStreamLoop: Periodic 1min renewal getInfo(stream=true)")
                    remote.getInfo(stream = true)
                }
            }
        }
    }

    /**
     * Stops the system info stream loop (called when navigating away from System tab).
     */
    fun stopSysInfoStream() {
        Log.d(TAG, "stopSysInfoStream()")
        sysInfoStreamJob?.cancel()
        sysInfoStreamJob = null
    }

    /**
     * Stops all active streams (called when exiting DeviceDetailScreen).
     */
    fun stopAllStreams() {
        Log.d(TAG, "stopAllStreams()")
        stopStatusStream()
        stopSysInfoStream()
    }

    // ── Public API ──

    suspend fun refreshStatus(stream: Boolean = false) {
        Log.d(TAG, "refreshStatus(stream=$stream)")
        remote.getStatus(stream)
    }

    suspend fun turnOn() {
        Log.d(TAG, "turnOn()")
        remote.turnOn()
    }

    suspend fun turnOff() {
        Log.d(TAG, "turnOff()")
        remote.turnOff()
    }

    suspend fun refreshConfig() {
        Log.d(TAG, "refreshConfig()")
        remote.getConfig()
    }

    suspend fun setConfig(updates: Map<String, Any>) {
        Log.d(TAG, "setConfig() updates=$updates")
        remote.setConfig(updates)
    }

    suspend fun setRemoteSwitchTarget(targetId: String, targetType: String, targetKey: String) {
        Log.d(TAG, "setRemoteSwitchTarget(targetId=$targetId, targetType=$targetType)")
        remote.setConfig(
            mapOf(
                "targetId" to targetId,
                "targetType" to targetType,
                "targetKey" to targetKey
            )
        )
    }

    suspend fun clearRemoteSwitchTarget() {
        Log.d(TAG, "clearRemoteSwitchTarget()")
        remote.setConfig(
            mapOf(
                "targetId" to "",
                "targetType" to "",
                "targetKey" to ""
            )
        )
    }

    suspend fun scanWifi() {
        Log.d(TAG, "scanWifi()")
        remote.scanWifi()
    }

    suspend fun getScanWifiData() {
        Log.d(TAG, "getScanWifiData()")
        remote.getScanWifiData()
    }

    suspend fun calibrate(payload: Map<String, Any>) {
        Log.d(TAG, "calibrate() payload=$payload")
        remote.calibrate(payload)
    }

    suspend fun resetCalibration() {
        Log.d(TAG, "resetCalibration()")
        remote.resetCalibration()
    }

    suspend fun setDeviceMode(pumpMode: Boolean) {
        Log.d(TAG, "setDeviceMode() pumpMode=$pumpMode")
        remote.setDeviceMode(pumpMode)
    }

    suspend fun refreshInfo(stream: Boolean = false) {
        Log.d(TAG, "refreshInfo(stream=$stream)")
        remote.getInfo(stream)
    }

    suspend fun reboot() {
        Log.d(TAG, "reboot()")
        remote.reboot()
    }

    suspend fun factoryReset() {
        Log.d(TAG, "factoryReset()")
        remote.factoryReset()
    }

    suspend fun clearPumpFault() {
        Log.d(TAG, "clearPumpFault()")
        remote.clearPumpFault()
    }

    suspend fun setLogMqtt(enabled: Boolean) {
        Log.d(TAG, "setLogMqtt($enabled)")
        _isLogEnabled.value = enabled
        remote.setLogMqtt(enabled)
    }

    suspend fun getLogMqtt() {
        Log.d(TAG, "getLogMqtt()")
        remote.getLogMqtt()
    }

    suspend fun sendRaw(rawInput: String, useBinary: Boolean = true): Boolean {
        Log.d(TAG, "sendRaw($rawInput, useBinary=$useBinary)")
        return remote.sendRaw(rawInput, useBinary)
    }

    suspend fun sendRawJson(rawJson: String): Boolean {
        return sendRaw(rawJson, useBinary = false)
    }

    fun reconnect() {
        Log.d(TAG, "reconnect() restarting channel")
        channel.restart()
    }

    fun switchDevice() {
        Log.d(TAG, "switchDevice() clearing cached device state and restarting channel")
        stopAllStreams()
        lastStatusReceivedTime = 0L
        _statusLatencyMs.value = null
        _isStatusStale.value = false
        _pumpStatus.value = null
        _deviceConfig.value = null
        _deviceInfo.value = null
        _isLogEnabled.value = false
        channel.restart()
    }

    companion object {
        private const val TAG = "PumpRepository"
        private const val STREAM_RENEWAL_INTERVAL_MS = 60_000L // 1 minute
    }
}
