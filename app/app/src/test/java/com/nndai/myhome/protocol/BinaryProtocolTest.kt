package com.nndai.myhome.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BinaryProtocolTest {

    @Test
    fun testGoldenGetStatusResponse() {
        // Construct a sample getStatus response
        val json = JSONObject()
        json.put("cmd", "getStatus")
        json.put("status", "ok")
        json.put("relay", true)
        json.put("current", 1.23)
        json.put("power", 280.5)
        json.put("voltage", 228.0)
        
        // Serialize
        val bytes = BinaryProtocolParser.serialize(json)
        
        // Ensure magic bytes
        assertEquals(0xB7.toByte(), bytes[0])
        assertEquals(0x01.toByte(), bytes[1])
        
        // Print hex for the C++ golden test
        val hex = bytes.joinToString(", ") { "0x%02X".format(it) }
        println("Golden Hex Array: [$hex]")
        
        // Parse back
        val parsedJson = BinaryProtocolParser.parse(bytes)
        assertNotNull("Failed to parse bytes", parsedJson)
        
        // Verify
        assertEquals("getStatus", parsedJson!!.optString("cmd"))
        assertEquals("ok", parsedJson.optString("status"))
        assertEquals(true, parsedJson.optBoolean("relay"))
        assertEquals(1.23, parsedJson.optDouble("current"), 0.001)
        assertEquals(280.5, parsedJson.optDouble("power"), 0.001)
        assertEquals(228.0, parsedJson.optDouble("voltage"), 0.001)
    }
}
