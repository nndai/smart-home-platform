package com.nndai.myhome.presentation.device.profiles.pump.log

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nndai.myhome.data.di.PumpRepositoryProvider
import com.nndai.myhome.data.repository.LogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LogViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = PumpRepositoryProvider.provide()
    private val logRepository: LogRepository = PumpRepositoryProvider.provideLogRepository()

    val isLogEnabled: StateFlow<Boolean> = repository.isLogEnabled
    val userMessages: SharedFlow<String> = logRepository.userMessages

    // 0 = Live MQTT Log, 1 = File Log (/logs/sys/)
    private val _logTabMode = MutableStateFlow(0)
    val logTabMode: StateFlow<Int> = _logTabMode.asStateFlow()

    private val _selectedSysDate = MutableStateFlow(LogRepository.getTodayDateStr())
    val selectedSysDate: StateFlow<String> = _selectedSysDate.asStateFlow()

    val availableSysDates: StateFlow<List<String>> = logRepository.availableSysDates
    val dailySysLogs: StateFlow<Map<String, String>> = logRepository.dailySysLogs
    val isSyncing: StateFlow<Boolean> = logRepository.isSyncing

    // Keep the latest logs (max 2000)
    val logs = mutableStateListOf<String>()

    init {
        // Fetch current device log state
        viewModelScope.launch {
            repository.getLogMqtt()
        }

        viewModelScope.launch {
            repository.logs.collect { msg ->
                if (logs.size >= 2000) {
                    logs.removeAt(0)
                }
                logs.add(msg)
            }
        }

        viewModelScope.launch {
            availableSysDates.collect { dates ->
                if (dates.isNotEmpty() && _selectedSysDate.value !in dates) {
                    _selectedSysDate.value = dates.first()
                }
            }
        }
    }

    fun setLogTabMode(mode: Int) {
        _logTabMode.value = mode
        if (mode == 1) {
            syncSysLogs(force = false)
        }
    }

    fun selectSysDate(dateStr: String) {
        _selectedSysDate.value = dateStr
    }

    fun syncSysLogs(force: Boolean = true) {
        logRepository.syncSysLogs(force)
    }

    fun deleteSysLogFile(dateStr: String) {
        logRepository.deleteSysLogFile(dateStr)
    }

    fun setLogEnabled(enabled: Boolean) {
        if (enabled) {
            logs.clear()
        }
        viewModelScope.launch {
            repository.setLogMqtt(enabled)
        }
    }

    fun clearLogs() {
        logs.clear()
    }

    fun sendRaw(rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return
        if (logs.size >= 2000) {
            logs.removeAt(0)
        }
        logs.add("[TX] $trimmed")
        viewModelScope.launch {
            repository.sendRaw(trimmed)
        }
    }

    fun sendRawJson(rawJson: String) {
        sendRaw(rawJson)
    }
}
