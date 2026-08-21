package com.nndai.myhome.presentation.device.common.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nndai.myhome.data.di.PumpRepositoryProvider
import com.nndai.myhome.data.model.ToggleLogEvent
import com.nndai.myhome.data.repository.LogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ToggleHistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val logRepository: LogRepository = PumpRepositoryProvider.provideLogRepository()

    private val _selectedToggleDate = MutableStateFlow(LogRepository.getTodayDateStr())
    val selectedToggleDate: StateFlow<String> = _selectedToggleDate.asStateFlow()

    val availableDates: StateFlow<List<String>> = logRepository.availableDates
    val isSyncing: StateFlow<Boolean> = logRepository.isSyncing

    /**
     * Danh sách sự kiện bật/tắt của ngày chọn, đảo ngược thứ tự để sự kiện mới nhất nằm ở trên.
     */
    val toggleEvents: StateFlow<List<ToggleLogEvent>> = combine(
        logRepository.dailyToggleLogs,
        _selectedToggleDate
    ) { toggleMap, dateStr ->
        (toggleMap[dateStr] ?: emptyList()).reversed()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        syncData()
    }

    fun selectToggleDate(dateStr: String) {
        _selectedToggleDate.value = dateStr
    }

    fun syncData(force: Boolean = false) {
        viewModelScope.launch {
            logRepository.syncLogs(force)
        }
    }
}
