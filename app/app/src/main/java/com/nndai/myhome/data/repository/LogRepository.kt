package com.nndai.myhome.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.nndai.myhome.data.model.DailyEnergyLog
import com.nndai.myhome.data.model.HourlyEnergyLog
import com.nndai.myhome.data.model.MonthlyEnergyLog
import com.nndai.myhome.data.model.RemoteFileEntry
import com.nndai.myhome.data.model.ToggleLogEvent
import com.nndai.myhome.data.model.ToggleSource
import com.nndai.myhome.data.remote.PumpCommandDataSource
import com.nndai.myhome.data.remote.PumpCommandEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Repository quản lý dữ liệu Lịch sử Năng Lượng (Power Logs) và Lịch sử Bật/Tắt (Toggle Logs).
 * Hỗ trợ xác thực reqId và offset để tránh xung đột khi nhiều thiết bị dùng chung 1 máy chủ MQTT.
 */
class LogRepository(
    private val context: Context,
    val deviceId: String,
    private val remote: PumpCommandDataSource,
    private val scope: CoroutineScope
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("device_logs_$deviceId", Context.MODE_PRIVATE)

    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    private val _availableDates = MutableStateFlow<List<String>>(emptyList())
    val availableDates: StateFlow<List<String>> = _availableDates.asStateFlow()

    private val _dailyPowerLogs = MutableStateFlow<Map<String, DailyEnergyLog>>(emptyMap())
    val dailyPowerLogs: StateFlow<Map<String, DailyEnergyLog>> = _dailyPowerLogs.asStateFlow()

    private val _dailyToggleLogs = MutableStateFlow<Map<String, List<ToggleLogEvent>>>(emptyMap())
    val dailyToggleLogs: StateFlow<Map<String, List<ToggleLogEvent>>> = _dailyToggleLogs.asStateFlow()

    private val _dailySysLogs = MutableStateFlow<Map<String, String>>(emptyMap())
    val dailySysLogs: StateFlow<Map<String, String>> = _dailySysLogs.asStateFlow()

    private val _availableSysDates = MutableStateFlow<List<String>>(emptyList())
    val availableSysDates: StateFlow<List<String>> = _availableSysDates.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val segmentCache = com.nndai.myhome.data.local.SegmentLogCache(context, deviceId)
    private val segmentReader = com.nndai.myhome.data.local.SegmentLogReader(segmentCache)
    private val coordinator = LogSyncCoordinator(remote, segmentCache) {
        _userMessages.emit("Một phần lịch sử trên thiết bị đã bị xóa. Dữ liệu đã tải vẫn còn trong bộ nhớ đệm.")
    }
    private val legacySync = LegacyLogSync(remote, prefs)
    private val syncRequests = kotlinx.coroutines.channels.Channel<Pair<List<Int>, Boolean>>(
        capacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    private var supportsV2 = false
    @Volatile private var v2SysDates: Set<String> = emptySet()
    private val _sysSegments = MutableStateFlow<List<com.nndai.myhome.data.model.LogSegment>>(emptyList())
    val sysSegments: StateFlow<List<com.nndai.myhome.data.model.LogSegment>> = _sysSegments.asStateFlow()

    fun loadSysSegments() {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try { fetchSysSegments() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { _userMessages.emit(error.message ?: "Không thể tải danh sách segment") }
        }
    }

    private suspend fun fetchSysSegments() {
        val segments = mutableListOf<com.nndai.myhome.data.model.LogSegment>()
        var offset = 0
        var revision: Long? = null
        var boot: Long? = null
        do {
            val result = coordinator.request("listLogSegments", JSONObject().apply {
                put("stream", 2); put("offset", offset)
                revision?.let { put("catalogRevision", it) }
                boot?.let { put("bootId", it) }
            })
            check(result.optString("status") == "ok") { "Danh sách segment đã thay đổi hoặc thiết bị chưa hỗ trợ. Hãy tải lại." }
            revision = result.getLong("catalogRevision"); boot = result.getLong("bootId")
            val entries = result.getJSONArray("entries")
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                segments += com.nndai.myhome.data.model.LogSegment(2, result.getLong("generation"),
                    entry.getLong("segmentId"), entry.getLong("size"), entry.getLong("minEpoch"),
                    entry.getLong("maxEpoch"), entry.getBoolean("sealed"))
            }
            offset += entries.length()
            check(offset <= 16 && (!result.optBoolean("more") || entries.length() > 0))
        } while (result.optBoolean("more"))
        _sysSegments.value = segments
    }

    fun deleteSegment(segment: com.nndai.myhome.data.model.LogSegment) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val result = coordinator.request("deleteLogSegment", JSONObject().apply {
                    put("stream", segment.stream); put("generation", segment.generation); put("segmentId", segment.id)
                })
                check(result.optString("status") == "ok") { "Không thể xóa segment: ${result.optString("status")}" }
                _userMessages.emit("Đã xóa segment trên thiết bị. Bản đã tải vẫn còn trong bộ nhớ đệm.")
                fetchSysSegments()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { _userMessages.emit(error.message ?: "Không thể xóa segment") }
        }
    }


    init {
        loadCachedData()
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { refreshSegmentData() }.onFailure { Log.w(TAG, "Cannot load segment cache", it) }
            for ((streams, force) in syncRequests) performSync(streams, force)
        }
        scope.launch {
            remote.events.collect { event ->
                when (event) {
                    is PumpCommandEvent.DeleteItemResult -> handleDeleteItemResult(event)
                    is PumpCommandEvent.StatusUpdate -> handleStatusUpdate(event)
                    else -> {}
                }
            }
        }
    }

    /**
     * Nạp dữ liệu đã lưu từ SharedPreferences (KEY_RAW_CONTENT) lên bộ nhớ khi mở ứng dụng.
     */
    private fun loadCachedData() {
        val powerMap = mutableMapOf<String, DailyEnergyLog>()
        val toggleMap = mutableMapOf<String, List<ToggleLogEvent>>()
        val datesSet = mutableSetOf<String>()

        // 1. Load từ JSON cache trước (có thể chứa dữ liệu realtime đã gộp)
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(PREFIX_POWER) && value is String) {
                val dateStr = key.removePrefix(PREFIX_POWER)
                val dailyLog = parseCachedPowerJson(dateStr, value)
                if (dailyLog != null) {
                    powerMap[dateStr] = dailyLog
                    datesSet.add(dateStr)
                }
            } else if (key.startsWith(PREFIX_TOGGLE) && value is String) {
                val dateStr = key.removePrefix(PREFIX_TOGGLE)
                val events = parseCachedToggleJson(value)
                toggleMap[dateStr] = events
                datesSet.add(dateStr)
            }
        }

        val sysMap = mutableMapOf<String, String>()
        val sysDatesSet = mutableSetOf<String>()

        // 2. Chỉ load từ RAW CONTENT nếu chưa có trong JSON (tránh ghi đè dữ liệu realtime đã lưu)
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(KEY_RAW_CONTENT) && value is String && value.isNotEmpty()) {
                val fullPath = key.removePrefix(KEY_RAW_CONTENT)
                val filename = fullPath.substringAfterLast("/")
                val dateStr = filename.removeSuffix(".log").let {
                    if (it == "nosync" || it == "01-01-1970") "Chưa xác định" else it
                }
                if (dateStr.isNotBlank()) {
                    if (fullPath.contains("/power/")) {
                        if (!powerMap.containsKey(dateStr)) {
                            val dailyLog = parsePowerLogData(dateStr, value)
                            powerMap[dateStr] = dailyLog
                            datesSet.add(dateStr)
                        }
                    } else if (fullPath.contains("/toggle/")) {
                        if (!toggleMap.containsKey(dateStr)) {
                            val events = parseToggleLogData(value)
                            toggleMap[dateStr] = events
                            datesSet.add(dateStr)
                        }
                    } else if (fullPath.contains("/sys/")) {
                        sysMap[dateStr] = value
                        sysDatesSet.add(dateStr)
                    }
                }
            }
        }

        _dailyPowerLogs.value = powerMap
        _dailyToggleLogs.value = toggleMap
        _availableDates.value = sortDates(datesSet.toList())
        _dailySysLogs.value = sysMap
        _availableSysDates.value = sortDates(sysDatesSet.toList())
    }

    fun syncLogs(force: Boolean = false) = startSync(listOf(0, 1), force)
    fun syncSysLogs(force: Boolean = true) = startSync(listOf(2), force)

    private fun startSync(streams: List<Int>, force: Boolean) {
        syncRequests.trySend(streams to force)
    }

    private suspend fun performSync(streams: List<Int>, force: Boolean) {
        _isSyncing.value = true
        try {
            supportsV2 = coordinator.sync(streams, force)
            val importKey = "legacy_imported_v2"
            val pendingLegacy = streams.filter { !supportsV2 || !prefs.getBoolean("$importKey$it", false) }
            if (pendingLegacy.isNotEmpty()) {
                legacySync.sync(pendingLegacy)
                if (supportsV2) prefs.edit().apply {
                    pendingLegacy.forEach { putBoolean("$importKey$it", true) }
                }.commit()
                loadCachedData()
            }
            refreshSegmentData()
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            _userMessages.emit("Thiết bị chưa phản hồi đồng bộ log. Vui lòng thử lại.")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Log sync failed", error)
            _userMessages.emit(error.message ?: "Không thể đồng bộ lịch sử")
        } finally {
            runCatching { refreshSegmentData() }.onFailure { Log.w(TAG, "Cannot refresh segment cache", it) }
            _isSyncing.value = false
        }
    }

    private fun refreshSegmentData() {
        for (stream in 0..2) {
            val type = listOf("power", "toggle", "sys")[stream]
            val grouped = segmentReader.grouped(stream).toMutableMap()
            if (stream == 2) v2SysDates = grouped.keys.toSet()
            val legacyUnknown = prefs.getString(KEY_RAW_CONTENT + "/logs/$type/nosync.log", "").orEmpty() +
                prefs.getString(KEY_RAW_CONTENT + "/logs/$type/01-01-1970.log", "").orEmpty()
            if (legacyUnknown.isNotEmpty()) grouped.putIfAbsent("Chưa xác định", "")
            // A later time anchor can move previously undated records into a real day.
            if ("Chưa xác định" !in grouped) {
                when (stream) {
                    1 -> {
                        _dailyToggleLogs.value = _dailyToggleLogs.value - "Chưa xác định"
                        prefs.edit().remove(PREFIX_TOGGLE + "Chưa xác định").apply()
                    }
                    2 -> _dailySysLogs.value = _dailySysLogs.value - "Chưa xác định"
                }
            }
            for ((date, content) in grouped) {
                val legacy = if (date == "Chưa xác định") legacyUnknown else
                    prefs.getString(KEY_RAW_CONTENT + "/logs/$type/$date.log", "").orEmpty()
                val combined = legacy + content
                when (stream) {
                    0 -> savePowerLogToCache(parsePowerLogData(date, combined))
                    1 -> saveToggleLogToCache(date, parseToggleLogData(combined))
                    2 -> saveSysLogToCache(date, combined)
                }
            }
        }
        _availableDates.value = sortDates((_dailyPowerLogs.value.keys + _dailyToggleLogs.value.keys).toList())
        _availableSysDates.value = sortDates(_dailySysLogs.value.keys.toList())
    }

    fun deleteSysLogFile(dateStr: String) {
        if (dateStr in v2SysDates) {
            scope.launch { _userMessages.emit("Ngày này nằm trong segment có thể chứa nhiều ngày. Hãy dùng mục Quản lý dữ liệu trên thiết bị để xem phạm vi và xóa.") }
            return
        }
        val fullPath = "/logs/sys/$dateStr.log"
        val reqId = "del_${System.currentTimeMillis()}"
        scope.launch {
            try {
                remote.deleteItem(fullPath, reqId)
            } catch (e: Exception) {
                _userMessages.emit("Không thể gửi lệnh xóa file $dateStr.log")
            }
        }
    }

    private fun handleDeleteItemResult(event: PumpCommandEvent.DeleteItemResult) {
        val fullPath = event.path
        val filename = fullPath.substringAfterLast("/")
        val dateStr = filename.removeSuffix(".log")

        if (event.success) {
            prefs.edit()
                .remove(KEY_RAW_CONTENT + fullPath)
                .remove(KEY_RAW_SIZE + fullPath)
                .apply()

            if (fullPath.contains("/sys/")) {
                val updatedMap = _dailySysLogs.value.toMutableMap()
                updatedMap.remove(dateStr)
                _dailySysLogs.value = updatedMap

                val updatedDates = _availableSysDates.value.filter { it != dateStr }
                _availableSysDates.value = updatedDates
            }

            scope.launch {
                _userMessages.emit("Đã xóa file $filename thành công!")
            }
        } else {
            scope.launch {
                val errMsg = event.message ?: "Xóa file thất bại"
                _userMessages.emit("Lỗi xóa file $filename: $errMsg")
            }
        }
    }

    // ── Parsers ──

    /**
     * Format file power: mỗi dòng chứa `hour|powerWh` (VD: 0|100 ... 23|75)
     */
    private fun parsePowerLogData(dateStr: String, content: String): DailyEnergyLog {
        val hourlyMap = (0..23).associateWith { 0L }.toMutableMap()
        content.lines().forEach { line ->
            val parts = line.trim().split("|")
            if (parts.size >= 2) {
                val hour = parts[0].trim().toIntOrNull()
                val powerWh = parts[1].trim().toLongOrNull()
                if (hour != null && hour in 0..23 && powerWh != null) {
                    hourlyMap[hour] = powerWh
                }
            }
        }

        val hourlyList = (0..23).map { h -> HourlyEnergyLog(h, hourlyMap[h] ?: 0L) }
        val totalWh = hourlyList.sumOf { it.energyWh }
        val isToday = (dateStr == getTodayDateStr())

        return DailyEnergyLog(
            dateStr = dateStr,
            totalWh = totalWh,
            hourlyList = hourlyList,
            isCompleteDay = !isToday
        )
    }

    /**
     * Format file toggle: mỗi dòng chứa `time|ToggleSource|state` (VD: 02:15:48.190|1|0)
     */
    private fun parseToggleLogData(content: String): List<ToggleLogEvent> {
        val list = mutableListOf<ToggleLogEvent>()
        content.lines().forEach { line ->
            val parts = line.trim().split("|")
            if (parts.size >= 3) {
                val timeStr = parts[0].trim()
                val srcCode = parts[1].trim().toIntOrNull() ?: -1
                val stateCode = parts[2].trim()
                val stateBool = (stateCode == "1" || stateCode.equals("true", ignoreCase = true))

                if (timeStr.isNotBlank()) {
                    list.add(
                        ToggleLogEvent(
                            timeStr = timeStr,
                            source = ToggleSource.fromCode(srcCode),
                            state = stateBool
                        )
                    )
                }
            }
        }
        return list
    }

    // ── Cache saving & parsing helpers ──

    private fun savePowerLogToCache(dailyLog: DailyEnergyLog) {
        val json = JSONObject().apply {
            put("totalWh", dailyLog.totalWh)
            put("isComplete", dailyLog.isCompleteDay)
            val arr = JSONArray()
            dailyLog.hourlyList.forEach { h ->
                arr.put(JSONObject().apply {
                    put("hour", h.hour)
                    put("wh", h.energyWh)
                })
            }
            put("hourly", arr)
        }

        prefs.edit().putString(PREFIX_POWER + dailyLog.dateStr, json.toString()).apply()

        val updatedMap = _dailyPowerLogs.value.toMutableMap()
        updatedMap[dailyLog.dateStr] = dailyLog
        _dailyPowerLogs.value = updatedMap

        val updatedDates = sortDates((_availableDates.value.toSet() + dailyLog.dateStr).toList())
        _availableDates.value = updatedDates
    }

    private fun parseCachedPowerJson(dateStr: String, jsonStr: String): DailyEnergyLog? {
        return try {
            val json = JSONObject(jsonStr)
            val totalWh = json.optLong("totalWh", 0L)
            val isComplete = json.optBoolean("isComplete", false)
            val arr = json.optJSONArray("hourly")
            val hourlyMap = (0..23).associateWith { 0L }.toMutableMap()

            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val hour = item.optInt("hour", -1)
                    val wh = item.optLong("wh", 0L)
                    if (hour in 0..23) {
                        hourlyMap[hour] = wh
                    }
                }
            }

            val hourlyList = (0..23).map { h -> HourlyEnergyLog(h, hourlyMap[h] ?: 0L) }

            DailyEnergyLog(
                dateStr = dateStr,
                totalWh = totalWh,
                hourlyList = hourlyList,
                isCompleteDay = isComplete
            )
        } catch (ex: Exception) {
            null
        }
    }

    private fun saveToggleLogToCache(dateStr: String, events: List<ToggleLogEvent>) {
        val arr = JSONArray()
        events.forEach { e ->
            arr.put(JSONObject().apply {
                put("timeStr", e.timeStr)
                put("source", e.source.code)
                put("state", e.state)
            })
        }

        prefs.edit().putString(PREFIX_TOGGLE + dateStr, arr.toString()).apply()

        val updatedMap = _dailyToggleLogs.value.toMutableMap()
        updatedMap[dateStr] = events
        _dailyToggleLogs.value = updatedMap

        val updatedDates = sortDates((_availableDates.value.toSet() + dateStr).toList())
        _availableDates.value = updatedDates
    }

    private fun saveSysLogToCache(dateStr: String, content: String) {
        val updatedMap = _dailySysLogs.value.toMutableMap()
        updatedMap[dateStr] = content
        _dailySysLogs.value = updatedMap

        val updatedDates = sortDates((_availableSysDates.value.toSet() + dateStr).toList())
        _availableSysDates.value = updatedDates
    }

    private fun parseCachedToggleJson(jsonStr: String): List<ToggleLogEvent> {
        val list = mutableListOf<ToggleLogEvent>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val timeStr = item.optString("timeStr", "")
                val srcCode = item.optInt("source", -1)
                val stateBool = item.optBoolean("state", false)
                if (timeStr.isNotBlank()) {
                    list.add(
                        ToggleLogEvent(
                            timeStr = timeStr,
                            source = ToggleSource.fromCode(srcCode),
                            state = stateBool
                        )
                    )
                }
            }
        } catch (ex: Exception) {
            // Ignore format error
        }
        return list
    }

    private fun mergeWithRealtimeData(parsedLog: DailyEnergyLog): DailyEnergyLog {
        // Dữ liệu từ file là chính xác nhất → ghi đè toàn bộ giá trị hiện tại
        // (kể cả giá trị tạm của giờ hiện tại, vì nếu file đã có giờ đó thì nó là số liệu cuối cùng)
        return parsedLog
    }

    private fun handleStatusUpdate(event: PumpCommandEvent.StatusUpdate) {
        val deviceEpoch = event.status.timestamp
        // Chỉ xử lý nếu thiết bị đã có thời gian thật (epoch >= 14-11-2023)
        if (deviceEpoch < 1_700_000_000L) return

        val realtimeEnergyWh = event.status.hourlyEnergy.toLong()
        if (realtimeEnergyWh <= 0L) return

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = deviceEpoch * 1000L
        }
        val curHour = cal.get(Calendar.HOUR_OF_DAY)
        Log.d(TAG, "realtimeEnergyWh=$realtimeEnergyWh, curHour=$curHour")
        val sdf = SimpleDateFormat("dd-MM-yyyy", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val todayStr = sdf.format(cal.time)

        val currentMap = _dailyPowerLogs.value
        val originalDailyLog = currentMap[todayStr] ?: DailyEnergyLog(
            dateStr = todayStr,
            totalWh = 0L,
            hourlyList = (0..23).map { HourlyEnergyLog(it, 0L) },
            isCompleteDay = false
        )

        val currentStoredEnergy = originalDailyLog.hourlyList.find { it.hour == curHour }?.energyWh ?: 0L

        if (realtimeEnergyWh > currentStoredEnergy) {
            val updatedHourlyList = originalDailyLog.hourlyList.map {
                if (it.hour == curHour) it.copy(energyWh = realtimeEnergyWh)
                else it
            }
            val updatedDailyLog = originalDailyLog.copy(
                hourlyList = updatedHourlyList,
                totalWh = updatedHourlyList.sumOf { it.energyWh }
            )
            savePowerLogToCache(updatedDailyLog)
        }
    }

    // ── Public Accessors ──

    /**
     * Lấy dữ liệu 6 tháng gần nhất để vẽ biểu đồ tháng.
     */
    fun get6MonthEnergyLogs(): List<MonthlyEnergyLog> {
        val sdfDate = SimpleDateFormat("dd-MM-yyyy", Locale.US)
        val sdfMonth = SimpleDateFormat("MM/yyyy", Locale.US)
        val monthlyTotals = mutableMapOf<String, Long>()

        _dailyPowerLogs.value.forEach { (dateStr, dailyLog) ->
            try {
                val date = sdfDate.parse(dateStr)
                if (date != null) {
                    val monthKey = sdfMonth.format(date)
                    monthlyTotals[monthKey] = (monthlyTotals[monthKey] ?: 0L) + dailyLog.totalWh
                }
            } catch (ex: Exception) {
                // Ignore parse error
            }
        }

        // Tạo 6 tháng gần nhất (gồm cả tháng hiện tại)
        val cal = Calendar.getInstance()
        val result = mutableListOf<MonthlyEnergyLog>()
        for (i in 5 downTo 0) {
            val tempCal = cal.clone() as Calendar
            tempCal.add(Calendar.MONTH, -i)
            val monthKey = sdfMonth.format(tempCal.time)
            result.add(MonthlyEnergyLog(monthKey, monthlyTotals[monthKey] ?: 0L))
        }
        return result
    }

    companion object {
        private const val TAG = "LogRepository"
        private const val PREFS_NAME = "energy_toggle_log_cache"
        private const val PREFIX_POWER = "power_log_"
        private const val PREFIX_TOGGLE = "toggle_log_"
        private const val KEY_RAW_CONTENT = "raw_content_"
        private const val KEY_RAW_SIZE = "raw_size_"
        private const val KEY_LAST_POWER_CHECK_DATE = "last_power_check_date"
        private const val KEY_LAST_POWER_CHECK_HOUR = "last_power_check_hour"
        private const val DIR_LIST_PAGE_LIMIT = 20

        fun getTodayDateStr(): String {
            return SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date())
        }

        private fun sortDates(dates: List<String>): List<String> {
            val sdf = SimpleDateFormat("dd-MM-yyyy", Locale.US)
            return dates.sortedByDescending {
                runCatching { sdf.parse(it) }.getOrNull() ?: Date(0)
            }
        }
    }
}
