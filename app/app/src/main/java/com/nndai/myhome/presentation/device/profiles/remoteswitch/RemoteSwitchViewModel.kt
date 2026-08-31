package com.nndai.myhome.presentation.device.profiles.remoteswitch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nndai.myhome.R
import com.nndai.myhome.data.di.PumpRepositoryProvider
import com.nndai.myhome.data.model.ConnectionState
import com.nndai.myhome.data.model.Device
import com.nndai.myhome.data.model.DeviceConfig
import com.nndai.myhome.data.model.PumpStatus
import com.nndai.myhome.data.pairing.ControlKeyCrypto
import com.nndai.myhome.data.pairing.ControlKeyStore
import com.nndai.myhome.data.remote.SupabaseConfig
import com.nndai.myhome.data.repository.DeviceManagerRepository
import com.nndai.myhome.data.repository.PumpRepository
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RemoteSwitchViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: PumpRepository = PumpRepositoryProvider.provide()
    private val keyStore = ControlKeyStore(application)
    private val deviceManager = DeviceManagerRepository(application)

    val deviceStatus: StateFlow<PumpStatus?> = repository.pumpStatus
    val deviceConfig: StateFlow<DeviceConfig?> = repository.deviceConfig
    val connectionState: StateFlow<ConnectionState> = repository.connectionState

    private val _isToggling = MutableStateFlow(false)
    val isToggling: StateFlow<Boolean> = _isToggling.asStateFlow()

    private val _isSettingTarget = MutableStateFlow(false)
    val isSettingTarget: StateFlow<Boolean> = _isSettingTarget.asStateFlow()

    private val _isClearingTarget = MutableStateFlow(false)
    val isClearingTarget: StateFlow<Boolean> = _isClearingTarget.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val currentUserId: String? = runCatching {
        SupabaseConfig.client.auth.currentSessionOrNull()?.user?.id
    }.getOrNull()

    /**
     * Danh sách thiết bị mà user đang sở hữu (loại trừ chính Remote Switch đang xem,
     * và chỉ lấy các loại profile mục tiêu hợp lệ như pump, switch).
     */
    val ownedCandidateDevices: StateFlow<List<Device>> = deviceManager.devices
        .map { allDevices ->
            val activeId = PumpRepositoryProvider.getActiveDeviceId()
            allDevices.filter { dev ->
                dev.device_id != activeId &&
                !dev.isTransferred(currentUserId) &&
                (dev.profile.equals("pump", ignoreCase = true) ||
                 dev.profile.equals("switch", ignoreCase = true) ||
                 dev.profile.equals("fan", ignoreCase = true))
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private var pingJob: Job? = null
    private var toggleTimeoutJob: Job? = null

    init {
        // Load danh sách thiết bị
        viewModelScope.launch {
            deviceManager.fetchDevices()
        }

        // Lắng nghe kết quả lệnh
        viewModelScope.launch {
            repository.commandEvents.collect { event ->
                when (event.command) {
                    "setRelay" -> {
                        toggleTimeoutJob?.cancel()
                        _isToggling.value = false
                        if (event.success) {
                            _messages.tryEmit(
                                if (event.message == "on") getString(R.string.rs_msg_target_on)
                                else getString(R.string.rs_msg_target_off)
                            )
                        } else {
                            _messages.tryEmit(event.message ?: getString(R.string.rs_msg_relay_failed))
                        }
                    }
                    "setConfig" -> {
                        _isSettingTarget.value = false
                        _isClearingTarget.value = false
                        if (event.success) {
                            _messages.tryEmit(getString(R.string.rs_msg_config_saved))
                            repository.refreshStatus(stream = true)
                            repository.refreshConfig()
                        } else {
                            _messages.tryEmit(event.message ?: getString(R.string.rs_msg_config_failed))
                        }
                    }
                }
            }
        }

        // Vòng lặp ping status khi kết nối
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

    /**
     * Kiểm tra xem thiết bị có controlKey trong local KeyStore không.
     */
    fun hasControlKey(deviceId: String): Boolean {
        return !keyStore.get(deviceId).isNullOrBlank()
    }

    /**
     * Thiết lập mục tiêu mới (Set Target):
     * - Lấy targetKey từ KeyStore.
     * - Lấy remoteKey từ KeyStore.
     * - Mã hóa E2E targetKey bằng AES-256-GCM với remoteKey.
     * - Gửi setConfig xuống Remote Switch.
     */
    fun setTarget(targetDevice: Device) {
        val activeDeviceId = PumpRepositoryProvider.getActiveDeviceId()
        if (activeDeviceId.isBlank()) {
            _messages.tryEmit(getString(R.string.rs_msg_unknown_switch))
            return
        }

        val targetKeyHex = keyStore.get(targetDevice.device_id)
        if (targetKeyHex.isNullOrBlank()) {
            _messages.tryEmit(getString(R.string.rs_msg_no_target_key, targetDevice.name))
            return
        }

        val remoteKeyHex = keyStore.get(activeDeviceId)
        if (remoteKeyHex.isNullOrBlank()) {
            _messages.tryEmit(getString(R.string.rs_msg_no_local_key))
            return
        }

        viewModelScope.launch {
            _isSettingTarget.value = true
            try {
                // Mã hóa E2E AES-256-GCM (128 hex chars)
                val encryptedTargetKey = ControlKeyCrypto.encryptTargetKey(targetKeyHex, remoteKeyHex)
                if (encryptedTargetKey == null) {
                    _isSettingTarget.value = false
                    _messages.tryEmit(getString(R.string.rs_msg_encrypt_failed))
                    return@launch
                }

                repository.setRemoteSwitchTarget(
                    targetId = targetDevice.device_id,
                    targetType = targetDevice.profile,
                    targetKey = encryptedTargetKey
                )
            } catch (e: Exception) {
                _isSettingTarget.value = false
                _messages.tryEmit(getString(R.string.rs_msg_send_failed_prefix, e.message ?: ""))
            }
        }
    }

    /**
     * Xóa liên kết mục tiêu (Clear Target).
     */
    fun clearTarget() {
        viewModelScope.launch {
            _isClearingTarget.value = true
            try {
                repository.clearRemoteSwitchTarget()
            } catch (e: Exception) {
                _isClearingTarget.value = false
                _messages.tryEmit(getString(R.string.rs_msg_clear_failed_prefix, e.message ?: ""))
            }
        }
    }

    /**
     * Bật/Tắt relay của thiết bị mục tiêu từ xa.
     */
    fun toggleRelay() {
        val status = deviceStatus.value ?: return
        viewModelScope.launch {
            _isToggling.value = true
            toggleTimeoutJob?.cancel()
            toggleTimeoutJob = launch {
                delay(8000L)
                if (_isToggling.value) {
                    _isToggling.value = false
                    _messages.tryEmit(getString(R.string.rs_msg_timeout))
                }
            }

            runCatching {
                if (status.relay) repository.turnOff() else repository.turnOn()
            }.onFailure {
                toggleTimeoutJob?.cancel()
                _isToggling.value = false
                _messages.tryEmit(getString(R.string.rs_msg_command_failed))
            }
        }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            repository.refreshStatus(stream = true)
            repository.refreshConfig()
        }
    }

    fun reconnect() {
        repository.reconnect()
    }

    private fun startPingLoop() {
        if (pingJob?.isActive == true) return
        pingJob = viewModelScope.launch {
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

    private fun getString(resId: Int): String =
        getApplication<Application>().getString(resId)

    private fun getString(resId: Int, arg: String): String =
        getApplication<Application>().getString(resId, arg)

    companion object {
        private const val PING_INTERVAL_MS = 60_000L
    }
}
