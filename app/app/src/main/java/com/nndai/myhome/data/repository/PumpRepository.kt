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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Single source of truth cho dữ liệu thiết bị.
 * Gom tất cả flow từ remote, cung cấp API thao tác cho ViewModel.
 */
class PumpRepository(
    private val remote: PumpCommandDataSource,
    private val channel: DeviceChannel,
    private val scope: CoroutineScope
) {

    private val _pumpStatus = MutableStateFlow<PumpStatus?>(null)
    val pumpStatus: StateFlow<PumpStatus?> = _pumpStatus.asStateFlow()

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

    init {
        Log.d(TAG, "init: start device channel")
        channel.start()

        // Collect remote events → update state flows
        scope.launch {
            remote.events.collect { event ->
                when (event) {
                    is PumpCommandEvent.StatusUpdate -> _pumpStatus.value = event.status
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

        // Auto-refresh when connected
        scope.launch {
            connectionState.collectLatest { state ->
                Log.d(TAG, "connection state=$state")
                if (state is ConnectionState.Connected) {
                    refreshStatus()
                    refreshConfig()
                    refreshInfo()
                }
            }
        }
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

    suspend fun sendRawJson(rawJson: String): Boolean {
        Log.d(TAG, "sendRawJson($rawJson)")
        return remote.sendRawJson(rawJson)
    }

    fun reconnect() {
        Log.d(TAG, "reconnect() restarting channel")
        channel.restart()
    }

    fun switchDevice() {
        Log.d(TAG, "switchDevice() clearing cached device state and restarting channel")
        _pumpStatus.value = null
        _deviceConfig.value = null
        _deviceInfo.value = null
        _isLogEnabled.value = false
        channel.restart()
    }

    companion object {
        private const val TAG = "PumpRepository"
    }
}
