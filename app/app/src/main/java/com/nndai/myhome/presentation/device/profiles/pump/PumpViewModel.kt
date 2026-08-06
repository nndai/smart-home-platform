package com.nndai.myhome.presentation.device.profiles.pump

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nndai.myhome.R
import com.nndai.myhome.data.di.PumpRepositoryProvider
import com.nndai.myhome.data.model.ConnectionState
import com.nndai.myhome.data.model.PumpStatus
import com.nndai.myhome.data.repository.PumpRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: PumpRepository = PumpRepositoryProvider.provide()

    val pumpStatus: StateFlow<PumpStatus?> = repository.pumpStatus
    val connectionState: StateFlow<ConnectionState> = repository.connectionState

    private val _isToggling = MutableStateFlow(false)
    val isToggling: StateFlow<Boolean> = _isToggling.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var pingJob: Job? = null
    private var toggleTimeoutJob: Job? = null

    init {
        // Observe command results
        viewModelScope.launch {
            repository.commandEvents.collect { event ->
                when (event.command) {
                    "setRelay" -> {
                        toggleTimeoutJob?.cancel()
                        _isToggling.value = false
                        if (event.success) {
                            _messages.tryEmit(
                                if (event.message == "on") getApplication<Application>().getString(R.string.pump_turned_on)
                                else getApplication<Application>().getString(R.string.pump_turned_off)
                            )
                        } else {
                            _messages.tryEmit(event.message ?: getApplication<Application>().getString(R.string.failed_update_relay))
                        }
                    }
                    "clearPumpFault" -> {
                        if (event.success) {
                            _messages.tryEmit(getApplication<Application>().getString(R.string.pump_fault_cleared))
                        } else {
                            _messages.tryEmit(event.message ?: getApplication<Application>().getString(R.string.failed_clear_fault))
                        }
                    }
                }
            }
        }

        // Start ping loop when connected
        viewModelScope.launch {
            connectionState.collect { state ->
                if (state is ConnectionState.Connected) {
                    startPingLoop()
                } else {
                    stopPingLoop()
                }
            }
        }
    }

    fun togglePump() {
        val status = pumpStatus.value ?: return
        viewModelScope.launch {
            _isToggling.value = true
            toggleTimeoutJob?.cancel()
            toggleTimeoutJob = launch {
                delay(10000L)
                if (_isToggling.value) {
                    _isToggling.value = false
                    _messages.tryEmit(getApplication<Application>().getString(R.string.relay_toggle_timeout))
                }
            }

            runCatching {
                if (status.relay) repository.turnOff() else repository.turnOn()
            }.onFailure {
                toggleTimeoutJob?.cancel()
                _isToggling.value = false
                _messages.tryEmit(getApplication<Application>().getString(R.string.failed_send_toggle))
            }
        }
    }

    fun clearPumpFault() {
        viewModelScope.launch {
            runCatching { repository.clearPumpFault() }
        }
    }

    fun reconnect() {
        repository.reconnect()
    }

    fun refreshStatus() {
        viewModelScope.launch {
            repository.refreshStatus(stream = true)
        }
    }

    /**
     * Gửi yêu cầu status với cờ stream=true mỗi 1 phút để MCU stream status mỗi 2s trong 2 phút.
     * App lắng nghe PumpStatus flow để tự động cập nhật.
     */
    private fun startPingLoop() {
        if (pingJob?.isActive == true) return
        pingJob = viewModelScope.launch {
            // Gửi ngay lập tức khi connected
            runCatching { repository.refreshStatus(stream = true) }
            while (isActive) {
                delay(PING_INTERVAL_MS)
                runCatching { repository.refreshStatus(stream = true) }
            }
        }
    }

    private fun stopPingLoop() {
        pingJob?.cancel()
        pingJob = null
    }

    companion object {
        /** Ping mỗi 1 phút. MCU sẽ stream status mỗi 2s trong 2 phút. */
        private const val PING_INTERVAL_MS = 60_000L
    }
}
