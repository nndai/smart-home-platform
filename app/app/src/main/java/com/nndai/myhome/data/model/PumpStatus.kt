package com.nndai.myhome.data.model

/**
 * Trạng thái realtime của pump, map từ response của command "getStatus".
 */
data class PumpStatus(
    val relay: Boolean = false,
    val current: Float = 0f,
    val power: Float = 0f,
    val voltage: Float = 0f,
    val dailyEnergy: Float = 0f,
    val hourlyEnergy: Float = 0f,
    val apparent: Float = 0f,
    val pf: Float = 0f,
    val temperature: Float = 0f,
    val rssi: Int = 0,
    val uptime: Long = 0,
    val heap: Long = 0,
    val pumpMode: Boolean = true,
    val pumpState: PumpState = PumpState.OFF,
    val timestamp: Long = 0L
)

enum class PumpState(val code: Int, val label: String, val isLatchedFault: Boolean) {
    OFF(0, "OFF", false),
    RUNNING_OK(1, "RUNNING OK", false),
    HIGH_CURRENT(2, "HIGH CURRENT", false),
    DRY_RUN(3, "DRY RUN", true),
    CRITICAL_CURRENT(4, "CRITICAL CURRENT", true),
    OVERLOAD(5, "OVERLOAD", true);

    companion object {
        fun fromCodeOrString(code: Int?, str: String?): PumpState {
            if (code != null) {
                entries.find { it.code == code }?.let { return it }
            }
            if (str != null) {
                val clean = str.trim().uppercase().replace("_", " ")
                entries.find { it.label == clean || it.name == str.trim().uppercase() }?.let { return it }
            }
            return OFF
        }
    }
}
