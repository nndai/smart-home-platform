package com.nndai.myhome.presentation.device.profiles.pump.deviceinfo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nndai.myhome.data.di.PumpRepositoryProvider
import com.nndai.myhome.data.model.DeviceInfo
import com.nndai.myhome.data.repository.PumpRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DeviceInfoViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: PumpRepository = PumpRepositoryProvider.provide()

    val deviceInfo: StateFlow<DeviceInfo?> = repository.deviceInfo

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun refreshInfo(stream: Boolean = false) {
        viewModelScope.launch {
            _isRefreshing.value = true
            runCatching { repository.refreshInfo(stream) }
            _isRefreshing.value = false
        }
    }
}
