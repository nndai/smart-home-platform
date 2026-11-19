package com.nndai.myhome.data.local

import java.util.zip.CRC32

/** Byte offsets refer to the framed UTF-8 bytes, never to Kotlin string lengths. */
object LogRecordCodec {
    const val HEADER_SIZE = 24
    const val RECORD_HEADER_SIZE = 20
    const val MAX_PAYLOAD = 400
    data class Record(val type: Int, val boot: Long, val uptime: Long, val epoch: Long, val text: String)
    data class Decoded(val records: List<Record>, val committed: Int)

    fun u32(bytes: ByteArray, offset: Int): Long = (0..3).fold(0L) { value, i ->
        value or ((bytes[offset + i].toLong() and 255) shl (8 * i))
    }

    private fun validCrc(bytes: ByteArray, start: Int, size: Int): Boolean {
        val crc = CRC32().apply { update(bytes, start, size - 4) }.value
        return crc == u32(bytes, start + size - 4)
    }

    fun decode(bytes: ByteArray, stream: Int, generation: Long, segment: Long, complete: Boolean): Decoded {
        if (bytes.size < HEADER_SIZE) {
            require(!complete) { "Incomplete segment header" }
            return Decoded(emptyList(), 0)
        }
        require(bytes.copyOfRange(0, 4).contentEquals("SHL2".toByteArray()) && bytes[4].toInt() == 2 &&
            bytes[5].toInt() == stream && u32(bytes, 8) == generation && u32(bytes, 12) == segment &&
            validCrc(bytes, 0, HEADER_SIZE)) { "Invalid segment header" }
        val records = mutableListOf<Record>()
        var offset = HEADER_SIZE
        while (offset + RECORD_HEADER_SIZE <= bytes.size) {
            require(bytes[offset].toInt() == 0x52 && bytes[offset + 1].toInt() == 2 &&
                bytes[offset + 2].toInt() in 1..2) { "Invalid record header" }
            val payloadSize = u32(bytes, offset + 4)
            require(payloadSize <= MAX_PAYLOAD) { "Oversized record" }
            val size = RECORD_HEADER_SIZE + payloadSize.toInt() + 4
            if (offset + size > bytes.size) break
            require(validCrc(bytes, offset, size)) { "Record checksum failed" }
            val text = bytes.copyOfRange(offset + RECORD_HEADER_SIZE, offset + size - 4)
                .toString(Charsets.UTF_8)
            records += Record(bytes[offset + 2].toInt(), u32(bytes, offset + 8),
                u32(bytes, offset + 12), u32(bytes, offset + 16), text)
            offset += size
        }
        require(!complete || offset == bytes.size) { "Snapshot ends inside a record" }
        return Decoded(records, offset)
    }
}
