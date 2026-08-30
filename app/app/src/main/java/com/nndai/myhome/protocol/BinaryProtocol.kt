package com.nndai.myhome.protocol

/**
 * Core constants for the custom Binary Protocol.
 * MAGIC_START (0xB7) denotes the beginning of a binary packet.
 * MAGIC_END (0xA5) denotes the end of a binary packet, used for validation.
 */
object BinaryProtocol {
    const val MAGIC_START: Byte = 0xB7.toByte()
    const val MAGIC_END: Byte = 0xA5.toByte()

    fun isValidFrame(raw: ByteArray?): Boolean {
        if (raw == null || raw.size < 3) return false
        return raw[0] == MAGIC_START && raw[raw.size - 1] == MAGIC_END
    }

    fun buildFrame(cmdId: Int, payloadBytes: ByteArray): ByteArray {
        val finalBytes = ByteArray(payloadBytes.size + 3)
        finalBytes[0] = MAGIC_START
        finalBytes[1] = cmdId.toByte()
        System.arraycopy(payloadBytes, 0, finalBytes, 2, payloadBytes.size)
        finalBytes[finalBytes.size - 1] = MAGIC_END
        return finalBytes
    }

    fun parseFrame(raw: ByteArray): Triple<Int, ByteArray, Boolean>? {
        if (!isValidFrame(raw)) return null
        val cmdId = raw[1].toInt() and 0xFF
        val payload = raw.copyOfRange(2, raw.size - 1)
        return Triple(cmdId, payload, true)
    }
}

/**
 * Data types supported by the TLV (Type-Length-Value) binary protocol.
 * The internal value is bounded by 5 bits (0-31 max).
 */
enum class BinaryType(val value: Int) {
    NULL(0),
    BOOL(1),
    UINT8(2),
    UINT16(3),
    UINT32(4),
    UINT64(5),
    INT8(6),
    INT16(7),
    INT32(8),
    INT64(9),
    FLOAT32(10),
    FLOAT64(11),
    STRING(12),
    BYTES(13),
    OBJECT(14),
    ARRAY(15);

    companion object {
        fun fromInt(value: Int): BinaryType? {
            return entries.find { it.value == value }
        }

        fun isVariableSize(type: BinaryType): Boolean {
            return type == STRING || type == BYTES || type == OBJECT || type == ARRAY
        }

        fun getFixedTypeSize(type: BinaryType): Int {
            return when (type) {
                NULL -> 0
                BOOL, UINT8, INT8 -> 1
                UINT16, INT16 -> 2
                UINT32, INT32, FLOAT32 -> 4
                UINT64, INT64, FLOAT64 -> 8
                else -> 0
            }
        }
    }
}
