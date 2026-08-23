package com.nndai.myhome.data.model

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import com.nndai.myhome.R

/**
 * Điện năng tiêu thụ theo từng giờ (0..23h).
 * Wh trong khoảng hour -> (hour+1) (ví dụ: hour=0 là từ 00:00 -> 01:00, hour=23 là từ 23:00 -> 00:00).
 */
data class HourlyEnergyLog(
    val hour: Int,
    val energyWh: Long = 0L
)

/**
 * Tổng hợp điện năng sử dụng trong 1 ngày (DD-MM-YYYY).
 */
data class DailyEnergyLog(
    val dateStr: String,
    val totalWh: Long = 0L,
    val hourlyList: List<HourlyEnergyLog> = (0..23).map { HourlyEnergyLog(it, 0L) },
    val isCompleteDay: Boolean = false
)

/**
 * Điện năng tiêu thụ từng ngày trong nửa tháng (1..15 hoặc 16..cuối tháng).
 */
data class HalfMonthDayEnergyLog(
    val day: Int,
    val dateStr: String,
    val totalWh: Long = 0L
)

/**
 * Tổng hợp điện năng tiêu thụ theo tháng (MM-YYYY hoặc YYYY-MM).
 */
data class MonthlyEnergyLog(
    val yearMonthStr: String, // MM/YYYY
    val totalWh: Long = 0L
) {
    val totalKwh: Float get() = totalWh / 1000f
}

/**
 * Nguồn bật/tắt relay.
 * Mapped từ LogManager.h (0: Button, 1: App/Web, 2: Schedule).
 */
enum class ToggleSource(val code: Int, val labelRes: Int) {
    BUTTON(0, R.string.toggle_source_button),
    ONLINE(1, R.string.toggle_source_online),
    SCHEDULE(2, R.string.toggle_source_schedule),
    UNKNOWN(-1, R.string.toggle_source_unknown);

    companion object {
        fun fromCode(code: Int): ToggleSource = when (code) {
            0 -> BUTTON
            1 -> ONLINE
            2 -> SCHEDULE
            else -> UNKNOWN
        }
    }
}

/**
 * Sự kiện bật/tắt relay.
 */
data class ToggleLogEvent(
    val timeStr: String, // HH:mm:ss.SSS
    val source: ToggleSource,
    val state: Boolean // true: ON, false: OFF
)

/**
 * File thông tin thư mục từ firmware.
 */
data class RemoteFileEntry(
    val name: String,
    val type: String, // "file" | "dir"
    val size: Long = 0L
)
