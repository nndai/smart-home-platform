package com.nndai.myhome.protocol

/**
 * Command IDs for the Binary Protocol.
 * Generated automatically from BinaryCommandIds.h for 100% parity.
 */
object BinaryCommandIds {
    const val UNKNOWN: Int = 0
    const val GET_STATUS: Int = 1
    const val GET_CONFIG: Int = 2
    const val SET_CONFIG: Int = 3
    const val GET_LOG: Int = 4
    const val CLEAR_SYS_LOG: Int = 5
    const val OTA_URL: Int = 6
    const val REBOOT: Int = 7
    const val FACTORY_RESET: Int = 8
    const val SET_LOG_MQTT: Int = 9
    const val GET_LOG_MQTT: Int = 10
    const val GET_LOG_STATS: Int = 11
    const val GET_SYSTEM_INFO: Int = 12
    const val UPLOAD_FIRMWARE_START: Int = 13
    const val UPLOAD_FIRMWARE_END: Int = 14
    const val UPLOAD_FIRMWARE_ABORT: Int = 15
    const val OTA_CHUNK: Int = 16
    const val SCAN_WIFI: Int = 17
    const val GET_SCAN_WIFI_DATA: Int = 18
    const val PAIR: Int = 19
    const val PROVISION: Int = 20
    const val LOG: Int = 27
    const val LIST_DIR: Int = 30
    const val READ_FILE: Int = 31
    const val FILE_INFO: Int = 32
    const val DELETE_ITEM: Int = 33
    const val FS_INFO: Int = 34
    const val DOWNLOAD_FILE: Int = 35
    const val OTA_PROGRESS: Int = 50
    const val OTA_RESULT: Int = 51
    const val BEGIN_UPLOAD_FIRMWARE_SUCCESS: Int = 52
    const val BEGIN_UPLOAD_FIRMWARE_FAILED: Int = 53
    const val SET_RELAY: Int = 100
    const val CALIBRATE: Int = 101
    const val RESET_CALIBRATION: Int = 102
    const val CLEAR_PUMP_FAULT: Int = 103

    fun commandIdToString(id: Int): String {
        return when (id) {
            GET_STATUS -> "getStatus"
            GET_CONFIG -> "getConfig"
            SET_CONFIG -> "setConfig"
            GET_LOG -> "getLog"
            CLEAR_SYS_LOG -> "clearSysLog"
            OTA_URL -> "otaUrl"
            REBOOT -> "reboot"
            FACTORY_RESET -> "factoryReset"
            SET_LOG_MQTT -> "setLogMqtt"
            GET_LOG_MQTT -> "getLogMqtt"
            GET_LOG_STATS -> "getLogStats"
            GET_SYSTEM_INFO -> "getSystemInfo"
            UPLOAD_FIRMWARE_START -> "uploadFirmwareStart"
            UPLOAD_FIRMWARE_END -> "uploadFirmwareEnd"
            UPLOAD_FIRMWARE_ABORT -> "uploadFirmwareAbort"
            OTA_CHUNK -> "otaChunk"
            SCAN_WIFI -> "scanWifi"
            GET_SCAN_WIFI_DATA -> "getScanWifiData"
            PAIR -> "pair"
            PROVISION -> "provision"
            LOG -> "log"
            LIST_DIR -> "listDir"
            READ_FILE -> "readFile"
            FILE_INFO -> "fileInfo"
            DELETE_ITEM -> "deleteItem"
            FS_INFO -> "fsInfo"
            DOWNLOAD_FILE -> "downloadFile"
            OTA_PROGRESS -> "otaProgress"
            OTA_RESULT -> "otaResult"
            BEGIN_UPLOAD_FIRMWARE_SUCCESS -> "beginUploadFirmwareSuccess"
            BEGIN_UPLOAD_FIRMWARE_FAILED -> "beginUploadFirmwareFailed"
            SET_RELAY -> "setRelay"
            CALIBRATE -> "calibrate"
            RESET_CALIBRATION -> "resetCalibration"
            CLEAR_PUMP_FAULT -> "clearPumpFault"
            else -> "unknown"
        }
    }

    fun stringToCommandId(cmd: String): Int {
        return when (cmd) {
            "getStatus" -> GET_STATUS
            "getConfig" -> GET_CONFIG
            "setConfig" -> SET_CONFIG
            "getLog" -> GET_LOG
            "clearSysLog" -> CLEAR_SYS_LOG
            "otaUrl" -> OTA_URL
            "reboot" -> REBOOT
            "factoryReset" -> FACTORY_RESET
            "setLogMqtt" -> SET_LOG_MQTT
            "getLogMqtt" -> GET_LOG_MQTT
            "getLogStats" -> GET_LOG_STATS
            "getSystemInfo" -> GET_SYSTEM_INFO
            "uploadFirmwareStart" -> UPLOAD_FIRMWARE_START
            "uploadFirmwareEnd" -> UPLOAD_FIRMWARE_END
            "uploadFirmwareAbort" -> UPLOAD_FIRMWARE_ABORT
            "otaChunk" -> OTA_CHUNK
            "scanWifi" -> SCAN_WIFI
            "getScanWifiData" -> GET_SCAN_WIFI_DATA
            "pair" -> PAIR
            "provision" -> PROVISION
            "log" -> LOG
            "listDir" -> LIST_DIR
            "readFile" -> READ_FILE
            "fileInfo" -> FILE_INFO
            "deleteItem" -> DELETE_ITEM
            "fsInfo" -> FS_INFO
            "downloadFile" -> DOWNLOAD_FILE
            "otaProgress" -> OTA_PROGRESS
            "otaResult" -> OTA_RESULT
            "beginUploadFirmwareSuccess" -> BEGIN_UPLOAD_FIRMWARE_SUCCESS
            "beginUploadFirmwareFailed" -> BEGIN_UPLOAD_FIRMWARE_FAILED
            "setRelay" -> SET_RELAY
            "calibrate" -> CALIBRATE
            "resetCalibration" -> RESET_CALIBRATION
            "clearPumpFault" -> CLEAR_PUMP_FAULT
            else -> UNKNOWN
        }
    }
}
