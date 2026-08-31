package com.nndai.myhome.data.remote

import com.nndai.myhome.data.model.DeviceConfig
import com.nndai.myhome.data.model.DeviceInfo
import com.nndai.myhome.data.model.PumpStatus
import com.nndai.myhome.data.model.RemoteFileEntry

data class WifiNetwork(
    val name: String,
    val rssi: Int,
    val bssid: String = "",
    val isEncrypt: Boolean = true
) {
    val ssid: String get() = name
}

/**
 * Sealed interface cho các event từ thiết bị.
 * Mỗi response JSON từ firmware được parse thành một event cụ thể.
 */
sealed interface PumpCommandEvent {

    /** Nhận được trạng thái pump realtime. */
    data class StatusUpdate(val status: PumpStatus) : PumpCommandEvent

    /** Nhận được cấu hình thiết bị. */
    data class ConfigUpdate(val config: DeviceConfig) : PumpCommandEvent

    /** Nhận được thông tin thiết bị. */
    data class InfoUpdate(val info: DeviceInfo) : PumpCommandEvent

    /** Kết quả của một command (turnOn, turnOff, setConfig, reboot...). */
    data class CommandResult(
        val command: String,
        val success: Boolean,
        val message: String? = null,
        val needReboot: Boolean = false
    ) : PumpCommandEvent

    /** Sự kiện thiết bị đã hoàn tất quét WiFi (chờ lấy danh sách). */
    object WifiScanCompleted : PumpCommandEvent

    /** Kết quả quét WiFi. */
    data class WifiScanResult(
        val success: Boolean,
        val message: String? = null,
        val networks: List<WifiNetwork> = emptyList()
    ) : PumpCommandEvent

    /** Kết quả liệt kê thư mục file. */
    data class ListDirResult(
        val path: String,
        val entries: List<RemoteFileEntry>,
        val total: Int = 0,
        val more: Boolean = false,
        val success: Boolean,
        val message: String? = null,
        val reqId: String? = null
    ) : PumpCommandEvent

    /** Kết quả đọc nội dung file dạng binary byte array. */
    data class ReadFileResult(
        val path: String,
        val data: ByteArray,
        val offset: Long,
        val size: Long,
        val more: Boolean,
        val success: Boolean,
        val message: String? = null,
        val reqId: String? = null
    ) : PumpCommandEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ReadFileResult
            return path == other.path &&
                    data.contentEquals(other.data) &&
                    offset == other.offset &&
                    size == other.size &&
                    more == other.more &&
                    success == other.success &&
                    message == other.message &&
                    reqId == other.reqId
        }

        override fun hashCode(): Int {
            var result = path.hashCode()
            result = 31 * result + data.contentHashCode()
            result = 31 * result + offset.hashCode()
            result = 31 * result + size.hashCode()
            result = 31 * result + more.hashCode()
            result = 31 * result + success.hashCode()
            result = 31 * result + (message?.hashCode() ?: 0)
            result = 31 * result + (reqId?.hashCode() ?: 0)
            return result
        }
    }

    /** Lỗi gửi/nhận. */
    data class Failure(val message: String) : PumpCommandEvent

    /** Nhận được log. */
    data class LogMessage(val message: String) : PumpCommandEvent

    /** Trạng thái log MQTT hiện tại. */
    data class LogMqttStatus(val enabled: Boolean) : PumpCommandEvent

    /** Kết quả xóa file/thư mục. */
    data class DeleteItemResult(
        val success: Boolean,
        val path: String,
        val reqId: String? = null,
        val message: String? = null
    ) : PumpCommandEvent
}
