package com.nndai.myhome.presentation.device.profiles.pump.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nndai.myhome.data.di.PumpRepositoryProvider
import com.nndai.myhome.data.model.DailyEnergyLog
import com.nndai.myhome.data.model.HalfMonthDayEnergyLog
import com.nndai.myhome.data.model.HourlyEnergyLog
import com.nndai.myhome.data.model.MonthlyEnergyLog
import com.nndai.myhome.data.model.ToggleLogEvent
import com.nndai.myhome.data.repository.LogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

class EnergyHistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val logRepository: LogRepository = PumpRepositoryProvider.provideLogRepository()

    private val currentCal = Calendar.getInstance()
    private val defaultMonthStr = String.format(
        Locale.US, "%02d/%04d",
        currentCal.get(Calendar.MONTH) + 1,
        currentCal.get(Calendar.YEAR)
    )

    private val _selectedPowerDate = MutableStateFlow(LogRepository.getTodayDateStr())
    val selectedPowerDate: StateFlow<String> = _selectedPowerDate.asStateFlow()

    private val _selectedToggleDate = MutableStateFlow(LogRepository.getTodayDateStr())
    val selectedToggleDate: StateFlow<String> = _selectedToggleDate.asStateFlow()

    private val _selectedHalfMonthDate = MutableStateFlow(defaultMonthStr)
    val selectedHalfMonthDate: StateFlow<String> = _selectedHalfMonthDate.asStateFlow()

    val availableDates: StateFlow<List<String>> = logRepository.availableDates
    val isSyncing: StateFlow<Boolean> = logRepository.isSyncing

    val availableMonths: StateFlow<List<String>> = combine(logRepository.availableDates) { dates ->
        val months = dates[0].mapNotNull { dateStr ->
            val parts = dateStr.split("-")
            if (parts.size >= 3) "${parts[1]}/${parts[2]}" else null
        }.distinct()
        if (months.contains(defaultMonthStr)) months else (listOf(defaultMonthStr) + months).distinct()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = listOf(defaultMonthStr)
    )

    val dailyEnergyLog: StateFlow<DailyEnergyLog?> = combine(
        logRepository.dailyPowerLogs,
        _selectedPowerDate
    ) { powerMap, dateStr ->
        powerMap[dateStr] ?: DailyEnergyLog(
            dateStr = dateStr,
            totalWh = 0L,
            hourlyList = (0..23).map { HourlyEnergyLog(it, 0L) }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    val halfMonthEnergyLogs: StateFlow<List<HalfMonthDayEnergyLog>> = combine(
        logRepository.dailyPowerLogs,
        _selectedHalfMonthDate
    ) { powerMap, yearMonthStr ->
        val cleanStr = yearMonthStr.replace("/", "-")
        val parts = cleanStr.split("-")
        if (parts.size >= 2) {
            val month = parts[0].toIntOrNull() ?: (currentCal.get(Calendar.MONTH) + 1)
            val year = parts[1].toIntOrNull() ?: currentCal.get(Calendar.YEAR)

            val cal = Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month - 1)
                set(Calendar.DAY_OF_MONTH, 1)
            }
            val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

            (1..daysInMonth).map { d ->
                val dateStr = String.format(Locale.US, "%02d-%02d-%04d", d, month, year)
                val log = powerMap[dateStr]
                HalfMonthDayEnergyLog(
                    day = d,
                    dateStr = dateStr,
                    totalWh = log?.totalWh ?: 0L
                )
            }
        } else {
            emptyList()
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val monthlyEnergyLogs: StateFlow<List<MonthlyEnergyLog>> = combine(
        logRepository.dailyPowerLogs,
        _selectedPowerDate
    ) { _, _ ->
        logRepository.get6MonthEnergyLogs()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    /**
     * Danh sách sự kiện bật/tắt của ngày chọn, đảo ngược thứ tự để giờ mới nhất nằm ở trên.
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

    fun selectPowerDate(dateStr: String) {
        _selectedPowerDate.value = dateStr
    }

    fun selectToggleDate(dateStr: String) {
        _selectedToggleDate.value = dateStr
    }

    fun selectHalfMonthDate(monthStr: String) {
        _selectedHalfMonthDate.value = monthStr
    }

    fun syncData(force: Boolean = false) {
        viewModelScope.launch {
            logRepository.syncLogs(force)
        }
    }
}
