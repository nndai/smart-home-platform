package com.nndai.myhome.data.model

/**
 * Thông tin hệ thống thiết bị, map từ response của command "getSystemInfo".
 */
data class DeviceInfo(
    val data: Map<String, Any> = emptyMap()
)
