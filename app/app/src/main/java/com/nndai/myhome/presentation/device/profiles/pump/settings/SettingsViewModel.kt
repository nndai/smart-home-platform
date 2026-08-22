package com.nndai.myhome.presentation.device.profiles.pump.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nndai.myhome.data.di.PumpRepositoryProvider
import com.nndai.myhome.data.model.ConnectionState
import com.nndai.myhome.data.model.DeviceConfig
import com.nndai.myhome.data.remote.WifiNetwork
import com.nndai.myhome.data.repository.PumpRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: PumpRepository = PumpRepositoryProvider.provide()

    val deviceConfig: StateFlow<DeviceConfig?> = repository.deviceConfig
    val connectionState: StateFlow<ConnectionState> = repository.connectionState

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _isRebooting = MutableStateFlow(false)
    val isRebooting: StateFlow<Boolean> = _isRebooting.asStateFlow()

    private val _showRebootPrompt = MutableStateFlow(false)
    val showRebootPrompt: StateFlow<Boolean> = _showRebootPrompt.asStateFlow()

    private val _isScanningWifi = MutableStateFlow(false)
    val isScanningWifi: StateFlow<Boolean> = _isScanningWifi.asStateFlow()

    private val _wifiNetworks = MutableStateFlow<List<WifiNetwork>>(emptyList())
    val wifiNetworks: StateFlow<List<WifiNetwork>> = _wifiNetworks.asStateFlow()

    private val _showWifiScanDialog = MutableStateFlow(false)
    val showWifiScanDialog: StateFlow<Boolean> = _showWifiScanDialog.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun showMessage(msg: String) {
        _messages.tryEmit(msg)
    }

    private var saveTimeoutJob: Job? = null
    private var rebootTimeoutJob: Job? = null
    private var wifiScanTimeoutJob: Job? = null

    init {
        viewModelScope.launch {
            repository.commandEvents.collect { event ->
                when (event.command) {
                    "setConfig" -> {
                        saveTimeoutJob?.cancel()
                        if (_isSaving.value) {
                            _isSaving.value = false
                            _messages.tryEmit(event.message ?: if (event.success) "Config saved" else "Save failed")
                            if (event.success) {
                                refreshConfig()
                                if (event.needReboot) {
                                    _showRebootPrompt.value = true
                                }
                            }
                        }
                    }
                    "reboot" -> {
                        rebootTimeoutJob?.cancel()
                        if (_isRebooting.value) {
                            _isRebooting.value = false
                            _messages.tryEmit(event.message ?: "Device rebooting...")
                        }
                    }
                    "calibrate", "resetCalibration" -> {
                        saveTimeoutJob?.cancel()
                        if (_isSaving.value) {
                            _isSaving.value = false
                            _messages.tryEmit(event.message ?: if (event.success) "Calibration updated" else "Calibration failed")
                            if (event.success) {
                                refreshConfig()
                            }
                        }
                    }
                    "factoryReset" -> {
                        _messages.tryEmit("Factory reset initiated, device rebooting...")
                    }
                }
            }
        }

        viewModelScope.launch {
            repository.wifiScanCompleted.collect {
                Log.d(TAG, "wifiScanCompleted event received, calling getScanWifiData()")
                runCatching { repository.getScanWifiData() }
            }
        }

        viewModelScope.launch {
            repository.wifiScanResults.collect { result ->
                wifiScanTimeoutJob?.cancel()
                _isScanningWifi.value = false
                if (result.success) {
                    _wifiNetworks.value = result.networks
                    _showWifiScanDialog.value = true
                    if (result.networks.isEmpty()) {
                        _messages.tryEmit("No Wi-Fi networks found")
                    }
                } else {
                    _messages.tryEmit(result.message ?: "WiFi scan failed")
                }
            }
        }
    }

    fun refreshConfig() {
        viewModelScope.launch {
            runCatching { repository.refreshConfig() }
        }
    }

    fun scanWifi() {
        if (_isScanningWifi.value) return
        viewModelScope.launch {
            _isScanningWifi.value = true
            runCatching { repository.scanWifi() }
                .onFailure {
                    _isScanningWifi.value = false
                    _messages.tryEmit("Failed to request Wi-Fi scan")
                    return@launch
                }

            wifiScanTimeoutJob?.cancel()
            wifiScanTimeoutJob = launch {
                delay(30000L)

                if (!_isScanningWifi.value) return@launch

                Log.d(TAG, "30s elapsed for scanWifi, checking connection state...")

                val currentState = repository.connectionState.value
                val isConnected = (currentState is ConnectionState.TransportReady || currentState is ConnectionState.Connected)

                if (isConnected) {
                    Log.d(TAG, "Device currently connected, sending getScanWifiData()")
                    runCatching { repository.getScanWifiData() }
                } else {
                    Log.d(TAG, "Device not connected, waiting for reconnection to send getScanWifiData()")
                    val startWaitTime = System.currentTimeMillis()
                    while (_isScanningWifi.value && (System.currentTimeMillis() - startWaitTime < 30000L)) {
                        val state = repository.connectionState.value
                        if (state is ConnectionState.TransportReady || state is ConnectionState.Connected) {
                            Log.d(TAG, "Reconnected! Sending getScanWifiData()")
                            runCatching { repository.getScanWifiData() }
                            break
                        }
                        delay(1000L)
                    }
                }

                delay(30000L)

                if (_isScanningWifi.value) {
                    _isScanningWifi.value = false
                    _messages.tryEmit("WiFi scan timed out (1 min)")
                }
            }
        }
    }

    fun dismissWifiScanDialog() {
        _showWifiScanDialog.value = false
    }

    fun saveConfig(updates: Map<String, Any>) {
        if (updates.isEmpty()) {
            _messages.tryEmit("No changes to save")
            return
        }
        viewModelScope.launch {
            _isSaving.value = true
            runCatching { repository.setConfig(updates) }
                .onFailure {
                    _isSaving.value = false
                    _messages.tryEmit("Failed to send config")
                    return@launch
                }
            saveTimeoutJob?.cancel()
            saveTimeoutJob = launch {
                delay(10000L)
                if (_isSaving.value) {
                    _isSaving.value = false
                    _messages.tryEmit("Save config timed out (10s)")
                }
            }
        }
    }

    fun reboot() {
        viewModelScope.launch {
            _isRebooting.value = true
            _showRebootPrompt.value = false
            runCatching { repository.reboot() }
                .onFailure {
                    _isRebooting.value = false
                    _messages.tryEmit("Failed to send reboot command")
                    return@launch
                }
            rebootTimeoutJob?.cancel()
            rebootTimeoutJob = launch {
                delay(10000L)
                if (_isRebooting.value) {
                    _isRebooting.value = false
                    _messages.tryEmit("Reboot command sent (10s timeout reached)")
                }
            }
        }
    }

    fun dismissRebootPrompt() {
        _showRebootPrompt.value = false
    }

    fun factoryReset() {
        viewModelScope.launch {
            runCatching { repository.factoryReset() }
                .onFailure { _messages.tryEmit("Failed to send factory reset command") }
        }
    }

    fun calibrate(payload: Map<String, Any>) {
        if (payload.isEmpty()) return
        viewModelScope.launch {
            _isSaving.value = true
            saveTimeoutJob?.cancel()
            saveTimeoutJob = launch {
                delay(10000L)
                if (_isSaving.value) {
                    _isSaving.value = false
                    _messages.tryEmit("Calibration request timed out (10s)")
                }
            }
            runCatching { repository.calibrate(payload) }
                .onFailure {
                    saveTimeoutJob?.cancel()
                    _isSaving.value = false
                    _messages.tryEmit("Failed to send calibration command")
                }
        }
    }

    fun resetCalibration() {
        viewModelScope.launch {
            _isSaving.value = true
            saveTimeoutJob?.cancel()
            saveTimeoutJob = launch {
                delay(10000L)
                if (_isSaving.value) {
                    _isSaving.value = false
                    _messages.tryEmit("Reset calibration timed out (10s)")
                }
            }
            runCatching { repository.resetCalibration() }
                .onFailure {
                    saveTimeoutJob?.cancel()
                    _isSaving.value = false
                    _messages.tryEmit("Failed to send reset calibration command")
                }
        }
    }

    companion object {
        private const val TAG = "SettingsViewModel"
    }
}
