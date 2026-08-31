package com.nndai.myhome.data.remote

import com.nndai.myhome.data.model.ConnectionState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface trừu tượng cho kênh giao tiếp đến thiết bị.
 * Hiện tại chỉ có MQTT, nhưng có thể mở rộng thêm WebSocket sau.
 */
interface DeviceChannel {
    /** Flow nhận payload raw bytes từ thiết bị. */
    val incoming: SharedFlow<ByteArray>

    /** Trạng thái kết nối hiện tại. */
    val state: StateFlow<ConnectionState>

    /** Gửi raw bytes đến thiết bị. Trả về true nếu gửi thành công. */
    suspend fun send(raw: ByteArray): Boolean

    /** Bắt đầu kết nối. */
    fun start()

    /** Ngắt kết nối. */
    fun stop()

    /** Khởi động lại kết nối. */
    fun restart() {
        stop()
        start()
    }
}
