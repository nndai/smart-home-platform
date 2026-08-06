package com.nndai.myhome.data.model

/**
 * Trạng thái kết nối tổng quát đến thiết bị RemotePump.
 *
 * Flow: Idle → Connecting → TransportReady → Connected → Disconnected
 *                                                      ↗
 *                                          Failed ──────
 */
sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Connecting : ConnectionState

    /** Transport layer connected (MQTT broker / WS opened), awaiting device handshake. */
    data class TransportReady(val channel: String) : ConnectionState

    /** Device handshake complete — fully operational. */
    data class Connected(val channel: String = "") : ConnectionState

    data class Disconnected(val reason: String? = null) : ConnectionState
    data class Failed(val error: Throwable? = null) : ConnectionState
}
