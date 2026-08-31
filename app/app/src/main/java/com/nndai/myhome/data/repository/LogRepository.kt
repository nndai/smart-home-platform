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
 * Session theo dõi yêu cầu đọc file chủ động của thiết bị hiện tại.
 */
private data class FileReadSession(
    val reqId: String,
    val expectedOffset: Long,
    val requestedAt: Long = System.currentTimeMillis()
)

private data class DirListPageState(
    val pendingEntries: MutableList<RemoteFileEntry> = mutableListOf(),
    var currentOffset: Int = 0,
    var expectedTotal: Int = 0
)

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

    private val pendingFileBuffers = mutableMapOf<String, java.io.ByteArrayOutputStream>()

    private val activeListDirReqIds = mutableSetOf<String>()
    private val pendingDirPages = mutableMapOf<String, DirListPageState>()
    private val activeReadFileSessions = mutableMapOf<String, FileReadSession>()

    init {
        loadCachedData()
        scope.launch {
            remote.events.collect { event ->
                when (event) {
                    is PumpCommandEvent.ListDirResult -> handleListDirResult(event)
                    is PumpCommandEvent.ReadFileResult -> handleReadFileResult(event)
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
                val dateStr = filename.removeSuffix(".log")
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

    /**
     * Bắt đầu đồng bộ danh sách file từ thiết bị (/logs/power/ và /logs/toggle/).
     * Hỗ trợ pagination: tự động lấy các trang tiếp theo nếu response có more=true.
     * Tự động restart nếu total thay đổi giữa các trang.
     */
    fun syncLogs(force: Boolean = false) {
        scope.launch {
            if (_isSyncing.value) return@launch
            _isSyncing.value = true

            // Đặt timeout 30s để tự động tắt trạng thái quay (isSyncing) nếu không nhận được phản hồi
            scope.launch {
                kotlinx.coroutines.delay(30_000)
                if (_isSyncing.value) {
                    Log.w(TAG, "Sync timeout! Resetting isSyncing to false.")
                    _isSyncing.value = false
                    activeListDirReqIds.clear()
                    pendingDirPages.clear()
                }
            }

            if (force) {
                Log.d(TAG, "syncLogs() - FORCE SYNC for both power and toggle")
                requestDirPage("/logs/toggle/", 0)
                requestDirPage("/logs/power/", 0)
                return@launch
            }

            val lastCheckDateStr = prefs.getString(KEY_LAST_POWER_CHECK_DATE, "") ?: ""
            val lastCheckHour = prefs.getInt(KEY_LAST_POWER_CHECK_HOUR, -1)

            var shouldCheckPower = false

            if (lastCheckDateStr.isEmpty() || lastCheckHour == -1) {
                shouldCheckPower = true
            } else {
                val sdf = SimpleDateFormat("dd-MM-yyyy", Locale.US)
                try {
                    val lastDate = sdf.parse(lastCheckDateStr)
                    if (lastDate != null) {
                        val cal = Calendar.getInstance()
                        cal.time = lastDate
                        cal.set(Calendar.HOUR_OF_DAY, lastCheckHour)
                        cal.set(Calendar.MINUTE, 0)
                        cal.set(Calendar.SECOND, 0)
                        cal.set(Calendar.MILLISECOND, 0)

                        // Giờ check tiếp theo = giờ check lần trước + 1h + 1m
                        cal.add(Calendar.HOUR_OF_DAY, 1)
                        cal.add(Calendar.MINUTE, 1)

                        val targetCheckTime = cal.timeInMillis
                        if (System.currentTimeMillis() >= targetCheckTime) {
                            shouldCheckPower = true
                        }
                    } else {
                        shouldCheckPower = true
                    }
                } catch (e: Exception) {
                    shouldCheckPower = true
                }
            }

            Log.d(
                TAG,
                "syncLogs() - sending listDir with unique reqId, shouldCheckPower=$shouldCheckPower"
            )

            requestDirPage("/logs/toggle/", 0)

            if (shouldCheckPower) {
                requestDirPage("/logs/power/", 0)
            } else {
                Log.d(TAG, "syncLogs() - Skip power listDir because time check rule is not met yet")
            }
        }
    }

    fun syncSysLogs(force: Boolean = true) {
        scope.launch {
            if (_isSyncing.value) return@launch
            _isSyncing.value = true

            scope.launch {
                kotlinx.coroutines.delay(30_000)
                if (_isSyncing.value) {
                    Log.w(TAG, "Sync sys timeout! Resetting isSyncing to false.")
                    _isSyncing.value = false
                    activeListDirReqIds.clear()
                    pendingDirPages.clear()
                }
            }

            requestDirPage("/logs/sys/", 0)
        }
    }

    fun deleteSysLogFile(dateStr: String) {
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

    private suspend fun requestDirPage(path: String, offset: Int) {
        val reqId = remote.listDir(path, offset = offset.toLong(), limit = DIR_LIST_PAGE_LIMIT.toLong())
        activeListDirReqIds += reqId
        if (offset == 0) {
            pendingDirPages[path] = DirListPageState()
        }
    }

    private fun handleListDirResult(event: PumpCommandEvent.ListDirResult) {
        if (!event.success) {
            Log.e(TAG, "ListDir failed for ${event.path}: ${event.message}")
            activeListDirReqIds.remove(event.reqId)
            pendingDirPages.remove(event.path)
            _isSyncing.value = false
            return
        }

        if (event.reqId.isNullOrEmpty() || event.reqId !in activeListDirReqIds) {
            Log.w(TAG, "Ignoring listDir response for ${event.path}: reqId mismatch or not ours")
            return
        }

        val state = pendingDirPages[event.path] ?: run {
            Log.w(TAG, "Ignoring listDir response for ${event.path}: no active page state")
            activeListDirReqIds.remove(event.reqId)
            return
        }

        // Phát hiện total thay đổi → restart pagination từ đầu
        if (state.expectedTotal != 0 && event.total > 0 && event.total != state.expectedTotal) {
            Log.w(TAG, "Total changed from ${state.expectedTotal} to ${event.total}, restarting pagination for ${event.path}")
            state.pendingEntries.clear()
            state.currentOffset = 0
            state.expectedTotal = event.total
            activeListDirReqIds.remove(event.reqId)
            scope.launch {
                requestDirPage(event.path, 0)
            }
            return
        }

        if (state.expectedTotal == 0 && event.total > 0) {
            state.expectedTotal = event.total
        }

        state.pendingEntries.addAll(event.entries)

        // Còn trang → request tiếp
        if (event.more) {
            state.currentOffset += DIR_LIST_PAGE_LIMIT
            activeListDirReqIds.remove(event.reqId)
            scope.launch {
                requestDirPage(event.path, state.currentOffset)
            }
            return
        }

        // Hoàn tất tất cả trang → xử lý entries
        activeListDirReqIds.remove(event.reqId)
        pendingDirPages.remove(event.path)
        processDirEntries(event.path, state.pendingEntries)
    }

    private fun processDirEntries(path: String, entries: List<RemoteFileEntry>) {
        val todayDateStr = getTodayDateStr()
        val cal = Calendar.getInstance()
        val curHour = cal.get(Calendar.HOUR_OF_DAY)

        if (path.contains("power")) {
            prefs.edit()
                .putString(KEY_LAST_POWER_CHECK_DATE, todayDateStr)
                .putInt(KEY_LAST_POWER_CHECK_HOUR, curHour)
                .apply()

            entries.filter { it.name.endsWith(".log") && !it.name.startsWith("nosync") }.forEach { entry ->
                val fullPath = "/logs/power/${entry.name}"
                val deviceSize = entry.size
                val savedFileSize = prefs.getLong(KEY_RAW_SIZE + fullPath, -1L)
                val savedContent = prefs.getString(KEY_RAW_CONTENT + fullPath, "") ?: ""

                when {
                    savedFileSize == -1L -> {
                        if (deviceSize > 0L) {
                            Log.d(TAG, "Power log $fullPath FIRST (deviceSize=$deviceSize). Download from 0")
                            scope.launch {
                                val reqId = remote.readFile(fullPath, offset = 0L, limit = 1024, encode = false)
                                activeReadFileSessions[fullPath] = FileReadSession(reqId = reqId, expectedOffset = 0L)
                            }
                        }
                    }
                    deviceSize > savedFileSize -> {
                        Log.d(TAG, "Power log $fullPath INCREMENTAL (deviceSize=$deviceSize > saved=$savedFileSize). Offset=$savedFileSize")
                        scope.launch {
                            val reqId = remote.readFile(fullPath, offset = savedFileSize, limit = 1024, encode = false)
                            activeReadFileSessions[fullPath] = FileReadSession(reqId = reqId, expectedOffset = savedFileSize)
                        }
                    }
                    deviceSize < savedFileSize -> {
                        Log.d(TAG, "Power log $fullPath REPLACED (deviceSize=$deviceSize < saved=$savedFileSize). Re-download from 0")
                        prefs.edit()
                            .remove(KEY_RAW_CONTENT + fullPath)
                            .remove(KEY_RAW_SIZE + fullPath)
                            .apply()
                        scope.launch {
                            val reqId = remote.readFile(fullPath, offset = 0L, limit = 1024, encode = false)
                            activeReadFileSessions[fullPath] = FileReadSession(reqId = reqId, expectedOffset = 0L)
                        }
                    }
                    else -> {
                        Log.d(TAG, "Power log $fullPath UNCHANGED (deviceSize=$deviceSize == saved=$savedFileSize).")
                        if (savedContent.isNotEmpty()) {
                            val dateStr = entry.name.removeSuffix(".log")
                            val dailyLog = parsePowerLogData(dateStr, savedContent)
                            val finalLog = mergeWithRealtimeData(dailyLog)
                            savePowerLogToCache(finalLog)
                        }
                    }
                }
            }
        } else if (path.contains("toggle")) {
            entries.filter { it.name.endsWith(".log") && !it.name.startsWith("nosync") }.forEach { entry ->
                val fullPath = "/logs/toggle/${entry.name}"
                val deviceSize = entry.size
                val savedFileSize = prefs.getLong(KEY_RAW_SIZE + fullPath, -1L)
                val savedContent = prefs.getString(KEY_RAW_CONTENT + fullPath, "") ?: ""

                when {
                    savedFileSize == -1L -> {
                        if (deviceSize > 0L) {
                            Log.d(TAG, "Toggle log $fullPath FIRST (deviceSize=$deviceSize). Download from 0")
                            scope.launch {
                                val reqId = remote.readFile(fullPath, offset = 0L, limit = 1024, encode = false)
                                activeReadFileSessions[fullPath] = FileReadSession(reqId = reqId, expectedOffset = 0L)
                            }
                        }
                    }
                    deviceSize > savedFileSize -> {
                        Log.d(TAG, "Toggle log $fullPath INCREMENTAL (deviceSize=$deviceSize > saved=$savedFileSize). Offset=$savedFileSize")
                        scope.launch {
                            val reqId = remote.readFile(fullPath, offset = savedFileSize, limit = 1024, encode = false)
                            activeReadFileSessions[fullPath] = FileReadSession(reqId = reqId, expectedOffset = savedFileSize)
                        }
                    }
                    deviceSize < savedFileSize -> {
                        Log.d(TAG, "Toggle log $fullPath REPLACED (deviceSize=$deviceSize < saved=$savedFileSize). Re-download from 0")
                        prefs.edit()
                            .remove(KEY_RAW_CONTENT + fullPath)
                            .remove(KEY_RAW_SIZE + fullPath)
                            .apply()
                        scope.launch {
                            val reqId = remote.readFile(fullPath, offset = 0L, limit = 1024, encode = false)
                            activeReadFileSessions[fullPath] = FileReadSession(reqId = reqId, expectedOffset = 0L)
                        }
                    }
                    else -> {
                        Log.d(TAG, "Toggle log $fullPath UNCHANGED (deviceSize=$deviceSize == saved=$savedFileSize).")
                        if (savedContent.isNotEmpty()) {
                            val dateStr = entry.name.removeSuffix(".log")
                            val events = parseToggleLogData(savedContent)
                            saveToggleLogToCache(dateStr, events)
                        }
                    }
                }
            }
        } else if (path.contains("sys")) {
            entries.filter { it.name.endsWith(".log") && !it.name.startsWith("nosync") }.forEach { entry ->
                val fullPath = "/logs/sys/${entry.name}"
                val deviceSize = entry.size
                val savedFileSize = prefs.getLong(KEY_RAW_SIZE + fullPath, -1L)
                val savedContent = prefs.getString(KEY_RAW_CONTENT + fullPath, "") ?: ""

                when {
                    savedFileSize == -1L -> {
                        if (deviceSize > 0L) {
                            Log.d(TAG, "Sys log $fullPath FIRST (deviceSize=$deviceSize). Download from 0")
                            scope.launch {
                                val reqId = remote.readFile(fullPath, offset = 0L, limit = 1024, encode = false)
                                activeReadFileSessions[fullPath] = FileReadSession(reqId = reqId, expectedOffset = 0L)
                            }
                        }
                    }
                    deviceSize > savedFileSize -> {
                        Log.d(TAG, "Sys log $fullPath INCREMENTAL (deviceSize=$deviceSize > saved=$savedFileSize). Offset=$savedFileSize")
                        scope.launch {
                            val reqId = remote.readFile(fullPath, offset = savedFileSize, limit = 1024, encode = false)
                            activeReadFileSessions[fullPath] = FileReadSession(reqId = reqId, expectedOffset = savedFileSize)
                        }
                    }
                    deviceSize < savedFileSize -> {
                        Log.d(TAG, "Sys log $fullPath REPLACED (deviceSize=$deviceSize < saved=$savedFileSize). Re-download from 0")
                        prefs.edit()
                            .remove(KEY_RAW_CONTENT + fullPath)
                            .remove(KEY_RAW_SIZE + fullPath)
                            .apply()
                        scope.launch {
                            val reqId = remote.readFile(fullPath, offset = 0L, limit = 1024, encode = false)
                            activeReadFileSessions[fullPath] = FileReadSession(reqId = reqId, expectedOffset = 0L)
                        }
                    }
                    else -> {
                        Log.d(TAG, "Sys log $fullPath UNCHANGED (deviceSize=$deviceSize == saved=$savedFileSize).")
                        if (savedContent.isNotEmpty()) {
                            val dateStr = entry.name.removeSuffix(".log")
                            saveSysLogToCache(dateStr, savedContent)
                        }
                    }
                }
            }
        }
        _isSyncing.value = false
    }

    private fun handleReadFileResult(event: PumpCommandEvent.ReadFileResult) {
        val fullPath = event.path
        if (!event.success) {
            Log.e(TAG, "ReadFile failed for $fullPath: ${event.message}")
            activeReadFileSessions.remove(fullPath)
            pendingFileBuffers.remove(fullPath)
            return
        }

        // Bắt buộc phải có reqId
        if (event.reqId.isNullOrEmpty()) {
            Log.w(TAG, "Ignoring ReadFileResult for $fullPath: reqId is missing")
            return
        }

        val session = activeReadFileSessions[fullPath]

        // 1. Phải khớp với reqId của session hiện tại trên máy này
        if (session == null || event.reqId != session.reqId) {
            Log.w(TAG, "Ignoring ReadFileResult for $fullPath: reqId ${event.reqId} != expected ${session?.reqId}")
            return
        }

        // 2. Phải khớp offset mong muốn
        if (event.offset != session.expectedOffset) {
            Log.w(TAG, "Ignoring ReadFileResult for $fullPath: offset ${event.offset} != expected ${session.expectedOffset}")
            return
        }

        val filename = fullPath.substringAfterLast("/")
        val dateStr = filename.removeSuffix(".log")
        if (dateStr.isBlank()) return

        // Nối dữ liệu mới tải từ offset vào dữ liệu raw đã lưu dưới máy
        val savedContent = prefs.getString(KEY_RAW_CONTENT + fullPath, "") ?: ""
        val savedFileSize = prefs.getLong(KEY_RAW_SIZE + fullPath, -1L)

        val stream = pendingFileBuffers.getOrPut(fullPath) {
            java.io.ByteArrayOutputStream().apply {
                if (event.offset > 0L && savedContent.isNotEmpty()) {
                    val savedBytes = savedContent.toByteArray(Charsets.UTF_8)
                    val prefixLen = if (savedFileSize in 1..savedBytes.size.toLong()) {
                        savedFileSize.toInt()
                    } else if (event.offset <= savedBytes.size.toLong()) {
                        event.offset.toInt()
                    } else {
                        savedBytes.size
                    }
                    write(savedBytes, 0, prefixLen)
                }
            }
        }

        stream.write(event.data)

        if (event.more) {
            val nextOffset = event.offset + event.data.size.toLong()
            Log.d(
                TAG,
                "ReadFile path=$fullPath has MORE data (total size=${event.size}). Requesting next chunk at offset=$nextOffset"
            )
            scope.launch {
                val nextReqId = remote.readFile(fullPath, offset = nextOffset, limit = 1024, encode = false)
                activeReadFileSessions[fullPath] = FileReadSession(reqId = nextReqId, expectedOffset = nextOffset)
            }
            return
        }

        // Tải hoàn tất tất cả các chunk mới của file
        activeReadFileSessions.remove(fullPath)
        val fullBytes = stream.toByteArray()
        val fullContent = String(fullBytes, Charsets.UTF_8)
        pendingFileBuffers.remove(fullPath)

        // Lưu dữ liệu raw + kích thước file gốc (dùng Long, tránh lỗi UTF-8)
        prefs.edit()
            .putString(KEY_RAW_CONTENT + fullPath, fullContent)
            .putLong(KEY_RAW_SIZE + fullPath, event.size)
            .apply()

        if (fullPath.contains("/power/")) {
            val dailyLog = parsePowerLogData(dateStr, fullContent)
            val finalLog = mergeWithRealtimeData(dailyLog)
            savePowerLogToCache(finalLog)
        } else if (fullPath.contains("/toggle/")) {
            val toggleEvents = parseToggleLogData(fullContent)
            saveToggleLogToCache(dateStr, toggleEvents)
        } else if (fullPath.contains("/sys/")) {
            saveSysLogToCache(dateStr, fullContent)
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
