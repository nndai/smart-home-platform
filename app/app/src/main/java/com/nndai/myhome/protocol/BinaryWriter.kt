package com.nndai.myhome.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance binary protocol serializer for Kotlin.
 * Outputs TLV fields packed in 16-bit headers (9-bit ID, 5-bit Type, 2-bit Reserved/Size-high)
 * with an optional 3rd byte (8-bit Size-low) for variable-size types.
 * Defaults to Big Endian ordering, enabling perfect parity with the C++ firmware.
 */
class BinaryWriter(capacity: Int = 1024) {

    var buffer = ByteBuffer.allocate(capacity).order(ByteOrder.BIG_ENDIAN)

    private fun ensureCapacity(needed: Int) {
        if (buffer.remaining() < needed) {
            val newBuffer = ByteBuffer.allocate(buffer.capacity() * 2 + needed).order(ByteOrder.BIG_ENDIAN)
            buffer.flip()
            newBuffer.put(buffer)
            buffer = newBuffer
        }
    }

    fun writeHeader(id: Int, type: BinaryType, size: Int = 0) {
        if (BinaryType.isVariableSize(type)) {
            ensureCapacity(3)
            val headerVal = (id shl 7) or (type.value shl 2) or ((size ushr 8) and 0x03)
            buffer.put((headerVal ushr 8).toByte())
            buffer.put(headerVal.toByte())
            buffer.put((size and 0xFF).toByte())
        } else {
            ensureCapacity(2)
            val headerVal = (id shl 7) or (type.value shl 2)
            buffer.put((headerVal ushr 8).toByte())
            buffer.put(headerVal.toByte())
        }
    }

    fun writeU8(id: Int, value: Int) {
        writeHeader(id, BinaryType.UINT8)
        ensureCapacity(1)
        buffer.put(value.toByte())
    }

    fun writeU16(id: Int, value: Int) {
        writeHeader(id, BinaryType.UINT16)
        ensureCapacity(2)
        buffer.putShort(value.toShort())
    }

    fun writeU32(id: Int, value: Long) {
        writeHeader(id, BinaryType.UINT32)
        ensureCapacity(4)
        buffer.putInt(value.toInt())
    }

    fun writeU64(id: Int, value: Long) {
        writeHeader(id, BinaryType.UINT64)
        ensureCapacity(8)
        buffer.putLong(value)
    }

    fun writeI8(id: Int, value: Int) {
        writeHeader(id, BinaryType.INT8)
        ensureCapacity(1)
        buffer.put(value.toByte())
    }

    fun writeI16(id: Int, value: Int) {
        writeHeader(id, BinaryType.INT16)
        ensureCapacity(2)
        buffer.putShort(value.toShort())
    }

    fun writeI32(id: Int, value: Int) {
        writeHeader(id, BinaryType.INT32)
        ensureCapacity(4)
        buffer.putInt(value)
    }

    fun writeI64(id: Int, value: Long) {
        writeHeader(id, BinaryType.INT64)
        ensureCapacity(8)
        buffer.putLong(value)
    }

    fun writeFloat32(id: Int, value: Float) {
        writeHeader(id, BinaryType.FLOAT32)
        ensureCapacity(4)
        buffer.putFloat(value)
    }

    fun writeFloat64(id: Int, value: Double) {
        writeHeader(id, BinaryType.FLOAT64)
        ensureCapacity(8)
        buffer.putDouble(value)
    }

    fun writeBool(id: Int, value: Boolean) {
        writeHeader(id, BinaryType.BOOL)
        ensureCapacity(1)
        buffer.put((if (value) 1 else 0).toByte())
    }
    
    fun writeNull(id: Int) {
        writeHeader(id, BinaryType.NULL)
    }

    fun writeString(id: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val size = bytes.size.coerceAtMost(1023)
        writeHeader(id, BinaryType.STRING, size)
        ensureCapacity(size)
        buffer.put(bytes, 0, size)
    }

    fun writeBytes(id: Int, bytes: ByteArray) {
        val size = bytes.size.coerceAtMost(1023)
        writeHeader(id, BinaryType.BYTES, size)
        ensureCapacity(size)
        buffer.put(bytes, 0, size)
    }

    fun writeRaw(bytes: ByteArray) {
        ensureCapacity(bytes.size)
        buffer.put(bytes)
    }

    class Container(private val writer: BinaryWriter, private val startPos: Int) {
        fun end() {
            val currentPos = writer.buffer.position()
            val size = (currentPos - startPos - 3).coerceAtMost(1023)
            
            val savedPos = writer.buffer.position()
            
            // Read byte 1 of header at startPos + 1
            writer.buffer.position(startPos + 1)
            val b1 = writer.buffer.get().toInt() and 0xFF
            val updatedB1 = (b1 and 0xFC) or ((size ushr 8) and 0x03)
            
            // Write back updated header byte 1 and size low byte
            writer.buffer.position(startPos + 1)
            writer.buffer.put(updatedB1.toByte())
            writer.buffer.put((size and 0xFF).toByte())
            
            writer.buffer.position(savedPos)
        }
    }

    fun beginObject(id: Int): Container {
        val pos = buffer.position()
        writeHeader(id, BinaryType.OBJECT, 0)
        return Container(this, pos)
    }

    fun beginArray(id: Int): Container {
        val pos = buffer.position()
        writeHeader(id, BinaryType.ARRAY, 0)
        return Container(this, pos)
    }

    fun toByteArray(): ByteArray {
        val size = buffer.position()
        val result = ByteArray(size)
        val temp = buffer.duplicate()
        temp.flip()
        temp.get(result)
        return result
    }
}
