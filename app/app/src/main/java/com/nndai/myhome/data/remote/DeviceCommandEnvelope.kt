package com.nndai.myhome.data.remote

import android.util.Log
import com.nndai.myhome.data.pairing.ControlKeyStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Ký envelope lệnh cho firmware (docs §3.2):
 *   canonical = "seq|ts|cmd|payloadJSONcompact|src"  (payload serialize compact,
 *               giữ thứ tự key — khớp ArduinoJson serializeJson)
 *   hmac      = HMAC-SHA256(controlKeyRaw32B, canonical) → hex 64 thường
 *   src       = appSenderId() ổn định — thiết bị track seq tăng RIÊNG cho từng
 *               sender (app, remote switch...) nên không khóa nhau
 * Thiết bị chỉ thực thi lệnh MQTT có envelope hợp lệ (seq > last_seq của sender,
 * ts ±60s, hmac đúng).
 */
class DeviceCommandEnvelope(context: android.content.Context) {
    private val keyStore = ControlKeyStore(context)

    fun sign(deviceId: String, rawCommandJson: String): String? {
        if (deviceId.isBlank()) return null
        val keyHex = keyStore.get(deviceId) ?: run {
            Log.w(TAG, "sign(): no controlKey for $deviceId")
            return null
        }
        val keyBytes = hexToBytes(keyHex) ?: run {
            Log.e(TAG, "sign(): bad controlKey hex length for $deviceId. Hex: '$keyHex', len: ${keyHex.length}")
            return null
        }

        val cmdJson = runCatching { Json.parseToJsonElement(rawCommandJson).jsonObject }
            .getOrElse { e ->
                Log.e(TAG, "sign(): cannot parse command: ${e.message}")
                return null
            }
        val cmd = cmdJson["cmd"]?.jsonPrimitive?.content ?: run {
            Log.e(TAG, "sign(): no cmd field")
            return null
        }
        val payload = cmdJson["payload"] as? JsonObject ?: JsonObject(emptyMap())

        val src = keyStore.appSenderId()
        val seq = keyStore.nextSeq(deviceId)
        // Firmware lấy giờ LOCAL cố định UTC+7 (NTPClient offset trong Config.h),
        // verify |ts - now| <= 60s → ts phải ở múi +7, không phải epoch UTC.
        val ts = (System.currentTimeMillis() / 1000) + TZ_OFFSET_SEC
        val payloadCompact = Json.encodeToString(JsonObject.serializer(), payload)
        val canonical = "$seq|$ts|$cmd|$payloadCompact|$src"
        val hmacHex = hmacSha256Hex(keyBytes, canonical) ?: return null

        val reqId = cmdJson["reqId"]?.jsonPrimitive?.content

        return buildJsonObject {
            put("cmd", cmd)
            if (reqId != null) {
                put("reqId", reqId)
            }
            put("payload", payload)
            put("seq", seq)
            put("ts", ts)
            put("src", src)
            put("hmac", hmacHex)
        }.toString()
    }

    private fun hmacSha256Hex(key: ByteArray, data: String): String? {
        return runCatching {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            mac.doFinal(data.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }.getOrElse {
            Log.e(TAG, "hmac failed: ${it.message}")
            null
        }
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0 || hex.length != 64) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    companion object {
        private const val TAG = "DeviceCommandEnvelope"
        // Khớp TZ_OFFSET_SEC trong firmware/include/Config.h (UTC+7 Việt Nam)
        private const val TZ_OFFSET_SEC = 7 * 3600L
    }
}
