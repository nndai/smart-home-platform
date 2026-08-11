package com.nndai.myhome.data.model

/**
 * Real-time dynamic health status of a smart device for UI rendering.
 */
enum class DeviceOnlineStatus {
    UNKNOWN,      // Initializing / state not determined yet
    HANDSHAKING,  // Handshake request sent, awaiting response
    ONLINE,       // Physical hardware connected & responding via MQTT
    OFFLINE       // Physical hardware disconnected or timed out
}
