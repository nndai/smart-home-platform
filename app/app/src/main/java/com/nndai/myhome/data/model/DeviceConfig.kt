package com.nndai.myhome.data.model

/**
 * Cấu hình thiết bị, map từ response của command "getConfig".
 */
data class DeviceConfig(
    val connMode: Int = 0,
    val mqttServer: String = "",
    val mqttPort: Int = 8883,
    val mqttUser: String = "",
    val mqttPass: String = "",
    val mqttTopic: String = "",
    val wifiSSID: String = "",
    val wifiPass: String = "",
    val apSSID: String = "",
    val apPass: String = "",
    val debugSSID: String = "",
    val debugPass: String = "",
    val debugIp: String = "",
    val debugGateway: String = "",
    val debugNetmask: String = "",
    val pumpMode: Boolean = true,
    val threshOff: Int = 100,
    val threshNoWater: Int = 2000,
    val threshRunning: Int = 5000,
    val threshOverload: Int = 20000,
    val dryTimeout: Int = 10000,
    val overloadTimeout: Int = 3000,
    val relayStartMode: Int = 0,
    val cCal: Double = 1.0,
    val vCal: Double = 1.0,
    val pCal: Double = 1.0,
    val sysLogFileEnabled: Boolean = false,
    val sysLogFileLevel: Int = 0,
    val firmware: String = "",
    val targetId: String = "",
    val targetType: String = "",
    val targetKey: String = ""
)

