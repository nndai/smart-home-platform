package com.nndai.myhome.presentation.pairing

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nndai.myhome.data.pairing.PairingDevice
import com.nndai.myhome.data.pairing.PairingRepository
import com.nndai.myhome.data.pairing.PairingState
import kotlinx.coroutines.flow.StateFlow

class PairingViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = PairingRepository(app, viewModelScope)

    val state: StateFlow<PairingState> = repository.state
    val scanDevices = repository.scanDevices
    val scanInProgress = repository.scanInProgress

    fun startScan() = repository.startScan()

    fun connectSystemChooser() = repository.connectSystemChooser()

    fun selectDevice(device: PairingDevice) = repository.selectDevice(device)

    fun scanWifiOnDevice() = repository.scanWifiOnDevice()

    fun pair(wifiSsid: String, wifiPass: String) = repository.pair(wifiSsid, wifiPass)

    fun retryClaim() = repository.retryClaim()

    fun controlKeyFor(deviceId: String): String? = repository.controlKeyFor(deviceId)

    fun retryFromFailure() = repository.retryFromFailure()

    override fun onCleared() {
        repository.cleanup()
    }
}
