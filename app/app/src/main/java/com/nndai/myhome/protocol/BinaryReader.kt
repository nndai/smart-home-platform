package com.nndai.myhome.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fast allocation-free binary protocol deserializer for Kotlin.
 * Efficiently extracts the 16-bit headers (9-bit ID, 5-bit Type, 2-bit Reserved/Size-high)
 * with an optional 3rd byte for variable-size types.
 */
class BinaryReader(private val buffer: ByteBuffer) {

    constructor(bytes: ByteArray) : this(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN))

    fun hasRemaining(): Boolean = buffer.hasRemaining()

    fun readHeader(): Triple<Int, BinaryType, Int>? {
        if (buffer.remaining() < 2) return null
        val b0 = buffer.get().toInt() and 0xFF
        val b1 = buffer.get().toInt() and 0xFF

        val headerVal = (b0 shl 8) or b1

        val id = (headerVal ushr 7) and 0x1FF
        val typeVal = (headerVal ushr 2) and 0x1F
        val sizeHigh = headerVal and 0x03

        val type = BinaryType.fromInt(typeVal) ?: return null
        
        val size = if (BinaryType.isVariableSize(type)) {
            if (buffer.remaining() < 1) return null
            val sizeLow = buffer.get().toInt() and 0xFF
            (sizeHigh shl 8) or sizeLow
        } else {
            BinaryType.getFixedTypeSize(type)
        }

        return Triple(id, type, size)
    }

    fun readU8(): Int = buffer.get().toInt() and 0xFF
    fun readU16(): Int = buffer.short.toInt() and 0xFFFF
    fun readU32(): Long = buffer.int.toLong() and 0xFFFFFFFFL
    fun readU64(): Long = buffer.long

    fun readI8(): Int = buffer.get().toInt()
    fun readI16(): Int = buffer.short.toInt()
    fun readI32(): Int = buffer.int
    fun readI64(): Long = buffer.long

    fun readFloat32(): Float = buffer.float
    fun readFloat64(): Double = buffer.double
    fun readBool(): Boolean = (buffer.get().toInt() != 0)

    fun readString(size: Int): String {
        val bytes = ByteArray(size)
        buffer.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    fun readBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        buffer.get(bytes)
        return bytes
    }

    fun skip(size: Int) {
        buffer.position(buffer.position() + size)
    }
}
