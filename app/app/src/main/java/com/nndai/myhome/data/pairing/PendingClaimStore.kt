package com.nndai.myhome.data.pairing

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class PendingClaim(
    val deviceId: String,
    val profile: String,
    val name: String,
    val controlKeyHex: String?,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Lưu tạm các claim chưa POST được lên Supabase (mất mạng/DNS ngay sau khi
 * rời AP thiết bị). DeviceManagerRepository.syncPendingClaims() sẽ đồng bộ
 * khi có mạng trở lại.
 */
class PendingClaimStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("pending_claims", Context.MODE_PRIVATE)

    fun getAll(): List<PendingClaim> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            Json.parseToJsonElement(raw).jsonArray.mapNotNull { el ->
                val o = el.jsonObject
                val deviceId = o["deviceId"]?.jsonPrimitive?.content ?: return@mapNotNull null
                PendingClaim(
                    deviceId = deviceId,
                    profile = o["profile"]?.jsonPrimitive?.content ?: "pump",
                    name = o["name"]?.jsonPrimitive?.content ?: "",
                    controlKeyHex = o["controlKeyHex"]?.jsonPrimitive?.content,
                    createdAt = o["createdAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                )
            }
        }.getOrElse {
            Log.e(TAG, "getAll() corrupt data: ${it.message}")
            emptyList()
        }
    }

    fun add(claim: PendingClaim) {
        val list = getAll().toMutableList()
        if (list.any { it.deviceId == claim.deviceId }) return
        list.add(claim)
        save(list)
    }

    fun remove(deviceId: String) {
        save(getAll().filterNot { it.deviceId == deviceId })
    }

    private fun save(list: List<PendingClaim>) {
        val arr = JsonArray(list.map { c ->
            buildJsonObject {
                put("deviceId", c.deviceId)
                put("profile", c.profile)
                put("name", c.name)
                c.controlKeyHex?.let { put("controlKeyHex", it) }
                put("createdAt", c.createdAt)
            }
        })
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "claims"
        private const val TAG = "PendingClaimStore"
    }
}
