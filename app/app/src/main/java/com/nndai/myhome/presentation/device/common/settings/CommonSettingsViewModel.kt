package com.nndai.myhome.presentation.device.common.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nndai.myhome.R
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

open class CommonSettingsViewModel(application: Application) : AndroidViewModel(application) {

    protected val repository: PumpRepository = PumpRepositoryProvider.provide()

    val deviceConfig: StateFlow<DeviceConfig?> = repository.deviceConfig
    val connectionState: StateFlow<ConnectionState> = repository.connectionState

    protected val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    protected val _isRebooting = MutableStateFlow(false)
    val isRebooting: StateFlow<Boolean> = _isRebooting.asStateFlow()

    protected val _showRebootPrompt = MutableStateFlow(false)
    val showRebootPrompt: StateFlow<Boolean> = _showRebootPrompt.asStateFlow()

    protected val _isScanningWifi = MutableStateFlow(false)
    val isScanningWifi: StateFlow<Boolean> = _isScanningWifi.asStateFlow()

    protected val _wifiNetworks = MutableStateFlow<List<WifiNetwork>>(emptyList())
    val wifiNetworks: StateFlow<List<WifiNetwork>> = _wifiNetworks.asStateFlow()

    protected val _showWifiScanDialog = MutableStateFlow(false)
    val showWifiScanDialog: StateFlow<Boolean> = _showWifiScanDialog.asStateFlow()

    protected val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun showMessage(msg: String) {
        _messages.tryEmit(msg)
    }

    protected var saveTimeoutJob: Job? = null
    protected var rebootTimeoutJob: Job? = null
    protected var wifiScanTimeoutJob: Job? = null

    init {
        viewModelScope.launch {
            repository.commandEvents.collect { event ->
                when (event.command) {
                    "setConfig" -> {
                        saveTimeoutJob?.cancel()
                        if (_isSaving.value) {
                            _isSaving.value = false
                            _messages.tryEmit(event.message ?: if (event.success) getString(R.string.cs_msg_saved) else getString(R.string.cs_msg_save_failed))
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
                            _messages.tryEmit(event.message ?: getString(R.string.cs_msg_rebooting_device))
                        }
                    }
                    "factoryReset" -> {
                        _messages.tryEmit(getString(R.string.cs_msg_factory_resetting))
                    }
                }
            }
        }

        viewModelScope.launch {
            repository.wifiScanCompleted.collect {
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
                        _messages.tryEmit(getString(R.string.cs_msg_no_wifi_found))
                    }
                } else {
                    _messages.tryEmit(result.message ?: getString(R.string.cs_msg_wifi_scan_failed))
                }
            }
        }

        viewModelScope.launch {
            repository.connectionState.collect { state ->
                if (state is ConnectionState.Connected) {
                    refreshConfig()
                }
            }
        }
    }

    fun refreshConfig() {
        viewModelScope.launch {
            repository.refreshConfig()
        }
    }

    fun scanWifi() {
        if (_isScanningWifi.value) return
        _isScanningWifi.value = true
        _wifiNetworks.value = emptyList()

        viewModelScope.launch {
            repository.scanWifi()
        }

        wifiScanTimeoutJob?.cancel()
        wifiScanTimeoutJob = viewModelScope.launch {
            delay(30000L)
            if (_isScanningWifi.value) {
                _isScanningWifi.value = false
                _messages.tryEmit(getString(R.string.cs_msg_wifi_scan_timeout))
            }
        }
    }

    fun dismissWifiScanDialog() {
        _showWifiScanDialog.value = false
    }

    fun dismissRebootPrompt() {
        _showRebootPrompt.value = false
    }

    fun saveConfig(updates: Map<String, Any>) {
        if (updates.isEmpty()) return
        _isSaving.value = true

        viewModelScope.launch {
            repository.setConfig(updates)
        }

        saveTimeoutJob?.cancel()
        saveTimeoutJob = viewModelScope.launch {
            delay(8000L)
            if (_isSaving.value) {
                _isSaving.value = false
                _messages.tryEmit(getString(R.string.cs_msg_save_timeout))
            }
        }
    }

    fun reboot() {
        _showRebootPrompt.value = false
        _isRebooting.value = true

        viewModelScope.launch {
            repository.reboot()
        }

        rebootTimeoutJob?.cancel()
        rebootTimeoutJob = viewModelScope.launch {
            delay(8000L)
            if (_isRebooting.value) {
                _isRebooting.value = false
                _messages.tryEmit(getString(R.string.cs_msg_reboot_sent))
            }
        }
    }

    fun factoryReset() {
        viewModelScope.launch {
            repository.factoryReset()
        }
    }

    protected fun getString(resId: Int): String =
        getApplication<Application>().getString(resId)
}
