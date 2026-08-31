package com.nndai.myhome.protocol

/**
 * Common Field IDs for the Binary Protocol.
 * Maps field names to 9-bit unsigned integer identifiers (0-511).
 * Generated automatically from BinaryFieldIds.h for 100% parity.
 */
object BinaryFieldIds {
    const val NONE: Int = 0
    const val UNKNOWN: Int = NONE // Alias for backwards compatibility
    const val SYSTEM: Int = 1
    const val CHIP_ID: Int = 2
    const val CHIP_MODEL: Int = 3
    const val CPU_FREQ: Int = 4
    const val SDK_VERSION: Int = 5
    const val BUILD_TIME: Int = 6
    const val BUILD_UNIX_TIME: Int = 7
    const val UPTIME: Int = 8
    const val TIME_SYS: Int = 9
    const val RESET_REASON: Int = 10
    const val MEMORY: Int = 20
    const val FREE_HEAP: Int = 21
    const val MIN_EVER_FREE_HEAP: Int = 22
    const val MAX_ALLOC_HEAP: Int = 23
    const val WIFI: Int = 30
    const val RSSI: Int = 31
    const val SSID: Int = 32
    const val IP: Int = 33
    const val MAC: Int = 34
    const val CHANNEL: Int = 35
    const val CONN_MODE: Int = 36
    const val TEMPERATURE: Int = 37
    const val STORAGE: Int = 40
    const val FLASH_SIZE: Int = 41
    const val FS_TOTAL: Int = 42
    const val FS_USED: Int = 43
    const val FLASH_MODE: Int = 44
    const val FLASH_SPEED: Int = 45
    const val TASKS: Int = 50
    const val TASK: Int = 51
    const val TASK_NAME: Int = 52
    const val TASK_PRIORITY: Int = 53
    const val TASK_STACK_WATER_MARK: Int = 54
    const val TASK_STATE: Int = 55
    const val STATUS: Int = 60
    const val MESSAGE: Int = 61
    const val TIMESTAMP: Int = 62
    const val REQ_ID: Int = 63
    const val CMD: Int = 64
    const val PAYLOAD: Int = 65
    const val FIELDS: Int = 66
    const val LOG: Int = 70
    const val LOG_SIZE: Int = 71
    const val TOGGLE_LOG_SIZE: Int = 72
    const val POWER_LOG_SIZE: Int = 73
    const val TOTAL_BYTES: Int = 74
    const val USED_BYTES: Int = 75
    const val TIME_SYNCED: Int = 76
    const val ENABLED: Int = 77
    const val URL: Int = 80
    const val SIZE: Int = 81
    const val DATA: Int = 82
    const val PROGRESS: Int = 83
    const val TOTAL: Int = 84
    const val PCT: Int = 85
    const val RECEIVED: Int = 86
    const val MQTT_SERVER: Int = 90
    const val MQTT_PORT: Int = 91
    const val MQTT_USER: Int = 92
    const val MQTT_PASS: Int = 93
    const val MQTT_TOPIC: Int = 94
    const val WIFI_S_S_I_D: Int = 95
    const val WIFI_PASS: Int = 96
    const val DEBUG_S_S_I_D: Int = 97
    const val DEBUG_PASS: Int = 98
    const val DEBUG_IP: Int = 99
    const val DEBUG_GATEWAY: Int = 100
    const val DEBUG_NETMASK: Int = 101
    const val DEVICE_ID: Int = 102
    const val AP_S_S_I_D: Int = 103
    const val PROFILE: Int = 104
    const val PAIRING_STATE: Int = 105
    const val SYS_LOG_FILE_ENABLED: Int = 106
    const val SYS_LOG_FILE_LEVEL: Int = 107
    const val NEED_REBOOT: Int = 108
    const val STREAM: Int = 109
    const val SEQ: Int = 110
    const val TS: Int = 111
    const val HMAC: Int = 112
    const val SRC: Int = 113
    const val STATE: Int = 120
    const val RELAY: Int = 121
    const val ON_DURATION: Int = 122
    const val CURRENT: Int = 123
    const val POWER: Int = 124
    const val VOLTAGE: Int = 125
    const val DAILY_ENERGY: Int = 126
    const val HOURLY_ENERGY: Int = 127
    const val APPARENT: Int = 128
    const val PF: Int = 129
    const val PUMP_MODE: Int = 130
    const val PUMP_STATE_STR: Int = 131
    const val PUMP_STATE: Int = 132
    const val RELAY_START_MODE: Int = 133
    const val THRESH_OFF: Int = 134
    const val THRESH_NO_WATER: Int = 135
    const val THRESH_RUNNING: Int = 136
    const val THRESH_OVERLOAD: Int = 137
    const val DRY_TIMEOUT: Int = 138
    const val OVERLOAD_TIMEOUT: Int = 139
    const val C_CAL: Int = 140
    const val V_CAL: Int = 141
    const val P_CAL: Int = 142
    const val PUMP: Int = 143
    const val ENCODE: Int = 150
    const val ENTRIES: Int = 151
    const val LIMIT: Int = 152
    const val MORE: Int = 153
    const val OFFSET: Int = 154
    const val PATH: Int = 155
    const val TYPE: Int = 156
    const val CONTROL_KEY: Int = 160
    const val SYS_LOG_SIZE: Int = 161
    const val WIFI_DROP: Int = 162
    const val TARGET_ID: Int = 163
    const val TARGET_TYPE: Int = 164
    const val TARGET_KEY: Int = 165
    const val TARGET_PAIRED: Int = 166
    const val TARGET_ERROR: Int = 167
    const val NAME: Int = 170
    const val PRIORITY: Int = 171
    const val STACK_WATER_MARK: Int = 172
    const val NETWORKS: Int = 173
    const val BSSID: Int = 174
    const val IS_ENCRYPT: Int = 175
    const val MSG: Int = 176

    fun getName(id: Int): String {
        return when (id) {
            SYSTEM -> "system"
            CHIP_ID -> "chipId"
            CHIP_MODEL -> "chipModel"
            CPU_FREQ -> "cpuFreq"
            SDK_VERSION -> "sdkVersion"
            BUILD_TIME -> "buildTime"
            BUILD_UNIX_TIME -> "buildUnixTime"
            UPTIME -> "uptime"
            TIME_SYS -> "timeSys"
            RESET_REASON -> "resetReason"
            MEMORY -> "memory"
            FREE_HEAP -> "freeHeap"
            MIN_EVER_FREE_HEAP -> "minEverFreeHeap"
            MAX_ALLOC_HEAP -> "maxAllocHeap"
            WIFI -> "wifi"
            RSSI -> "rssi"
            SSID -> "ssid"
            IP -> "ip"
            MAC -> "mac"
            CHANNEL -> "channel"
            CONN_MODE -> "connMode"
            TEMPERATURE -> "temperature"
            STORAGE -> "storage"
            FLASH_SIZE -> "flashSize"
            FS_TOTAL -> "fsTotal"
            FS_USED -> "fsUsed"
            FLASH_MODE -> "flashMode"
            FLASH_SPEED -> "flashSpeed"
            TASKS -> "tasks"
            TASK -> "task"
            TASK_NAME -> "taskName"
            TASK_PRIORITY -> "taskPriority"
            TASK_STACK_WATER_MARK -> "taskStackWaterMark"
            TASK_STATE -> "taskState"
            STATUS -> "status"
            MESSAGE -> "message"
            TIMESTAMP -> "timestamp"
            REQ_ID -> "reqId"
            CMD -> "cmd"
            PAYLOAD -> "payload"
            FIELDS -> "fields"
            LOG -> "log"
            LOG_SIZE -> "logSize"
            TOGGLE_LOG_SIZE -> "toggleLogSize"
            POWER_LOG_SIZE -> "powerLogSize"
            TOTAL_BYTES -> "totalBytes"
            USED_BYTES -> "usedBytes"
            TIME_SYNCED -> "timeSynced"
            ENABLED -> "enabled"
            URL -> "url"
            SIZE -> "size"
            DATA -> "data"
            PROGRESS -> "progress"
            TOTAL -> "total"
            PCT -> "pct"
            RECEIVED -> "received"
            MQTT_SERVER -> "mqttServer"
            MQTT_PORT -> "mqttPort"
            MQTT_USER -> "mqttUser"
            MQTT_PASS -> "mqttPass"
            MQTT_TOPIC -> "mqttTopic"
            WIFI_S_S_I_D -> "wifiSSID"
            WIFI_PASS -> "wifiPass"
            DEBUG_S_S_I_D -> "debugSSID"
            DEBUG_PASS -> "debugPass"
            DEBUG_IP -> "debugIp"
            DEBUG_GATEWAY -> "debugGateway"
            DEBUG_NETMASK -> "debugNetmask"
            DEVICE_ID -> "deviceId"
            AP_S_S_I_D -> "apSSID"
            PROFILE -> "profile"
            PAIRING_STATE -> "pairingState"
            SYS_LOG_FILE_ENABLED -> "sysLogFileEnabled"
            SYS_LOG_FILE_LEVEL -> "sysLogFileLevel"
            NEED_REBOOT -> "needReboot"
            STREAM -> "stream"
            SEQ -> "seq"
            TS -> "ts"
            HMAC -> "hmac"
            SRC -> "src"
            STATE -> "state"
            RELAY -> "relay"
            ON_DURATION -> "onDuration"
            CURRENT -> "current"
            POWER -> "power"
            VOLTAGE -> "voltage"
            DAILY_ENERGY -> "dailyEnergy"
            HOURLY_ENERGY -> "hourlyEnergy"
            APPARENT -> "apparent"
            PF -> "pf"
            PUMP_MODE -> "pumpMode"
            PUMP_STATE_STR -> "pumpStateStr"
            PUMP_STATE -> "pumpState"
            RELAY_START_MODE -> "relayStartMode"
            THRESH_OFF -> "threshOff"
            THRESH_NO_WATER -> "threshNoWater"
            THRESH_RUNNING -> "threshRunning"
            THRESH_OVERLOAD -> "threshOverload"
            DRY_TIMEOUT -> "dryTimeout"
            OVERLOAD_TIMEOUT -> "overloadTimeout"
            C_CAL -> "cCal"
            V_CAL -> "vCal"
            P_CAL -> "pCal"
            PUMP -> "pump"
            ENCODE -> "encode"
            ENTRIES -> "entries"
            LIMIT -> "limit"
            MORE -> "more"
            OFFSET -> "offset"
            PATH -> "path"
            TYPE -> "type"
            CONTROL_KEY -> "controlKey"
            SYS_LOG_SIZE -> "sysLogSize"
            WIFI_DROP -> "wifiDrop"
            TARGET_ID -> "targetId"
            TARGET_TYPE -> "targetType"
            TARGET_KEY -> "targetKey"
            TARGET_PAIRED -> "targetPaired"
            TARGET_ERROR -> "targetError"
            NAME -> "name"
            PRIORITY -> "priority"
            STACK_WATER_MARK -> "stackWaterMark"
            NETWORKS -> "networks"
            BSSID -> "bssid"
            IS_ENCRYPT -> "isEncrypt"
            MSG -> "msg"
            else -> "unknown"
        }
    }

    fun getId(name: String): Int {
        return when (name) {
            "system" -> SYSTEM
            "chipId" -> CHIP_ID
            "chipModel" -> CHIP_MODEL
            "cpuFreq" -> CPU_FREQ
            "sdkVersion" -> SDK_VERSION
            "buildTime" -> BUILD_TIME
            "buildUnixTime" -> BUILD_UNIX_TIME
            "uptime" -> UPTIME
            "timeSys" -> TIME_SYS
            "resetReason" -> RESET_REASON
            "memory" -> MEMORY
            "freeHeap" -> FREE_HEAP
            "minEverFreeHeap" -> MIN_EVER_FREE_HEAP
            "maxAllocHeap" -> MAX_ALLOC_HEAP
            "wifi" -> WIFI
            "rssi" -> RSSI
            "ssid" -> SSID
            "ip" -> IP
            "mac" -> MAC
            "channel" -> CHANNEL
            "connMode" -> CONN_MODE
            "temperature" -> TEMPERATURE
            "storage" -> STORAGE
            "flashSize" -> FLASH_SIZE
            "fsTotal" -> FS_TOTAL
            "fsUsed" -> FS_USED
            "flashMode" -> FLASH_MODE
            "flashSpeed" -> FLASH_SPEED
            "tasks" -> TASKS
            "task" -> TASK
            "taskName" -> TASK_NAME
            "taskPriority" -> TASK_PRIORITY
            "taskStackWaterMark" -> TASK_STACK_WATER_MARK
            "taskState" -> TASK_STATE
            "status" -> STATUS
            "message" -> MESSAGE
            "timestamp" -> TIMESTAMP
            "reqId" -> REQ_ID
            "cmd" -> CMD
            "payload" -> PAYLOAD
            "fields" -> FIELDS
            "log" -> LOG
            "logSize" -> LOG_SIZE
            "toggleLogSize" -> TOGGLE_LOG_SIZE
            "powerLogSize" -> POWER_LOG_SIZE
            "totalBytes" -> TOTAL_BYTES
            "usedBytes" -> USED_BYTES
            "timeSynced" -> TIME_SYNCED
            "enabled" -> ENABLED
            "url" -> URL
            "size" -> SIZE
            "data" -> DATA
            "progress" -> PROGRESS
            "total" -> TOTAL
            "pct" -> PCT
            "received" -> RECEIVED
            "mqttServer" -> MQTT_SERVER
            "mqttPort" -> MQTT_PORT
            "mqttUser" -> MQTT_USER
            "mqttPass" -> MQTT_PASS
            "mqttTopic" -> MQTT_TOPIC
            "wifiSSID" -> WIFI_S_S_I_D
            "wifiPass" -> WIFI_PASS
            "debugSSID" -> DEBUG_S_S_I_D
            "debugPass" -> DEBUG_PASS
            "debugIp" -> DEBUG_IP
            "debugGateway" -> DEBUG_GATEWAY
            "debugNetmask" -> DEBUG_NETMASK
            "deviceId" -> DEVICE_ID
            "apSSID" -> AP_S_S_I_D
            "profile" -> PROFILE
            "pairingState" -> PAIRING_STATE
            "sysLogFileEnabled" -> SYS_LOG_FILE_ENABLED
            "sysLogFileLevel" -> SYS_LOG_FILE_LEVEL
            "needReboot" -> NEED_REBOOT
            "stream" -> STREAM
            "seq" -> SEQ
            "ts" -> TS
            "hmac" -> HMAC
            "src" -> SRC
            "state" -> STATE
            "relay" -> RELAY
            "onDuration" -> ON_DURATION
            "current" -> CURRENT
            "power" -> POWER
            "voltage" -> VOLTAGE
            "dailyEnergy" -> DAILY_ENERGY
            "hourlyEnergy" -> HOURLY_ENERGY
            "apparent" -> APPARENT
            "pf" -> PF
            "pumpMode" -> PUMP_MODE
            "pumpStateStr" -> PUMP_STATE_STR
            "pumpState" -> PUMP_STATE
            "relayStartMode" -> RELAY_START_MODE
            "threshOff" -> THRESH_OFF
            "threshNoWater" -> THRESH_NO_WATER
            "threshRunning" -> THRESH_RUNNING
            "threshOverload" -> THRESH_OVERLOAD
            "dryTimeout" -> DRY_TIMEOUT
            "overloadTimeout" -> OVERLOAD_TIMEOUT
            "cCal" -> C_CAL
            "vCal" -> V_CAL
            "pCal" -> P_CAL
            "pump" -> PUMP
            "encode" -> ENCODE
            "entries" -> ENTRIES
            "limit" -> LIMIT
            "more" -> MORE
            "offset" -> OFFSET
            "path" -> PATH
            "type" -> TYPE
            "controlKey" -> CONTROL_KEY
            "sysLogSize" -> SYS_LOG_SIZE
            "wifiDrop" -> WIFI_DROP
            "targetId" -> TARGET_ID
            "targetType" -> TARGET_TYPE
            "targetKey" -> TARGET_KEY
            "targetPaired" -> TARGET_PAIRED
            "targetError" -> TARGET_ERROR
            "name" -> NAME
            "priority" -> PRIORITY
            "stackWaterMark" -> STACK_WATER_MARK
            "networks" -> NETWORKS
            "bssid" -> BSSID
            "isEncrypt" -> IS_ENCRYPT
            "msg" -> MSG
            "wifiSsid" -> WIFI_S_S_I_D
            else -> NONE
        }
    }
}
