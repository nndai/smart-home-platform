package com.nndai.myhome.core.utils

/**
 * Utility extensions cho formatting giá trị hiển thị.
 */

/** Format uptime seconds → "1d 2h 3m" */
fun Long.formatUptime(): String {
    val days = this / 86400
    val hours = (this % 86400) / 3600
    val minutes = (this % 3600) / 60
    val seconds = this % 60
    return buildString {
        if (days > 0) append("${days}d ")
        if (hours > 0 || days > 0) append("${hours}h ")
        if (minutes > 0 || hours > 0 || days > 0)append("${minutes}m ")
        append("${seconds}s")
    }.trim()
}

/** Format current (A). */
fun Float.formatCurrent(): String = "%.3f A".format(this)

/** Format power (W). */
fun Float.formatPower(): String = "%.1f W".format(this)

/** Format voltage. */
fun Float.formatVoltage(): String = "%.1f V".format(this)

/** Format apparent power (VA → kVA nếu > 1000). */
fun Float.formatApparentPower(): String {
    return if (this >= 1000) "%.1f kVA".format(this / 1000f)
    else "%.0f VA".format(this)
}

/** Format power factor (PF). */
fun Float.formatPf(): String = "%.2f".format(this)

/** Format energy (Wh → kWh nếu > 1000). */
fun Float.formatEnergy(): String {
    return if (this >= 1000) "%.3f kWh".format(this / 1000f)
    else "%.0f Wh".format(this)
}

/** Format temperature. */
fun Float.formatTemperature(): String = "%.1f°C".format(this)

/** Format bytes → KB / MB. */
fun Long.formatBytes(): String {
    return when {
        this >= 1_048_576 -> "%.1f MB".format(this / 1_048_576.0)
        this >= 1024 -> "%.0f KB".format(this / 1024.0)
        else -> "$this B"
    }
}

/** Format RSSI → dBm string. */
fun Int.formatRssi(): String = "$this dBm"
