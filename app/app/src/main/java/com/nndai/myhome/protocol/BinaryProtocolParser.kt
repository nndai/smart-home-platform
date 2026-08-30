package com.nndai.myhome.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * Top-level serialization engine for the Binary Protocol.
 * Effortlessly parses and serializes Java/Kotlin JSONObjects into the highly-compact 
 * binary byte layout, hiding the complexity of the bit-shifts from the rest of the application.
 */
object BinaryProtocolParser {

    fun serialize(json: JSONObject): ByteArray {
        val contentWriter = BinaryWriter()
        
        val cmdStr = json.optString("cmd", "")
        val cmdId = BinaryCommandIds.stringToCommandId(cmdStr)
        
        // Remove cmd so it's not serialized in the body
        val jsonCopy = JSONObject(json.toString())
        jsonCopy.remove("cmd")
        
        // If there is a nested "payload" JSONObject, hoist its keys into the root object
        if (jsonCopy.has("payload") && jsonCopy.get("payload") is JSONObject) {
            val payloadObj = jsonCopy.getJSONObject("payload")
            payloadObj.keys().forEach { k ->
                jsonCopy.put(k, payloadObj.get(k))
            }
            jsonCopy.remove("payload")
        }
        
        writeObject(contentWriter, BinaryFieldIds.NONE, jsonCopy)
        
        val contentBytes = contentWriter.toByteArray()
        return BinaryProtocol.buildFrame(cmdId, contentBytes)
    }

    private fun writeObject(writer: BinaryWriter, id: Int, obj: JSONObject) {
        val container = writer.beginObject(id)
        
        obj.keys().forEach { key ->
            val fieldId = BinaryFieldIds.getId(key)
            val value = obj.get(key)
            writeValue(writer, fieldId, value)
        }
        
        container.end()
    }

    private fun writeArray(writer: BinaryWriter, id: Int, array: JSONArray) {
        val container = writer.beginArray(id)
        
        for (i in 0 until array.length()) {
            val value = array.get(i)
            // For array elements, ID is usually 0
            writeValue(writer, 0, value)
        }
        
        container.end()
    }

    private fun writeValue(writer: BinaryWriter, id: Int, value: Any) {
        when (value) {
            JSONObject.NULL -> writer.writeNull(id)
            is Boolean -> writer.writeBool(id, value)
            is Int -> writer.writeI32(id, value)
            is Long -> writer.writeI64(id, value)
            is Float -> writer.writeFloat32(id, value)
            is Double -> writer.writeFloat64(id, value)
            is String -> writer.writeString(id, value)
            is JSONObject -> writeObject(writer, id, value)
            is JSONArray -> writeArray(writer, id, value)
        }
    }

    fun parse(raw: ByteArray): JSONObject? {
        val frame = BinaryProtocol.parseFrame(raw) ?: return null
        val cmdId = frame.first
        val payload = frame.second
        val cmdStr = BinaryCommandIds.commandIdToString(cmdId)
        
        val hexString = raw.joinToString(separator = " ") { byte -> "%02X".format(byte) }
        android.util.Log.d("BinaryProtocol", "RX Binary [size=${raw.size}, cmd=$cmdStr ($cmdId)]: $hexString")
        
        val reader = BinaryReader(payload)
        val header = reader.readHeader() ?: return null
        
        if (header.first != BinaryFieldIds.NONE || header.second != BinaryType.OBJECT) {
            return null
        }
        
        val obj = readObject(reader, header.third)
        
        if (cmdStr != "unknown") {
            obj.put("cmd", cmdStr)
        }
        
        android.util.Log.d("BinaryProtocol", "Parsed JSON: ${obj.toString(2)}")
        
        return obj
    }

    private fun readObject(reader: BinaryReader, size: Int): JSONObject {
        val obj = JSONObject()
        var bytesRead = 0
        while (reader.hasRemaining() && bytesRead < size) {
            val header = reader.readHeader() ?: break
            val id = header.first
            val type = header.second
            val elementSize = header.third
            val headerBytes = if (BinaryType.isVariableSize(type)) 3 else 2
            bytesRead += headerBytes

            val key = BinaryFieldIds.getName(id)
            val value = readValue(reader, type, elementSize)
            if (value != null) {
                obj.put(key, value)
            }
            bytesRead += elementSize
        }
        return obj
    }

    private fun readArray(reader: BinaryReader, size: Int): JSONArray {
        val array = JSONArray()
        var bytesRead = 0
        while (reader.hasRemaining() && bytesRead < size) {
            val header = reader.readHeader() ?: break
            val type = header.second
            val elementSize = header.third
            val headerBytes = if (BinaryType.isVariableSize(type)) 3 else 2
            bytesRead += headerBytes
            
            val value = readValue(reader, type, elementSize)
            if (value != null) {
                array.put(value)
            }
            bytesRead += elementSize
        }
        return array
    }

    private fun readValue(reader: BinaryReader, type: BinaryType, size: Int): Any? {
        return when (type) {
            BinaryType.NULL -> JSONObject.NULL
            BinaryType.BOOL -> reader.readBool()
            BinaryType.UINT8 -> reader.readU8()
            BinaryType.UINT16 -> reader.readU16()
            BinaryType.UINT32 -> reader.readU32()
            BinaryType.UINT64 -> reader.readU64()
            BinaryType.INT8 -> reader.readI8()
            BinaryType.INT16 -> reader.readI16()
            BinaryType.INT32 -> reader.readI32()
            BinaryType.INT64 -> reader.readI64()
            BinaryType.FLOAT32 -> reader.readFloat32().toDouble()
            BinaryType.FLOAT64 -> reader.readFloat64()
            BinaryType.STRING -> reader.readString(size)
            BinaryType.BYTES -> reader.readBytes(size)
            BinaryType.OBJECT -> readObject(reader, size)
            BinaryType.ARRAY -> readArray(reader, size)
        }
    }
}
