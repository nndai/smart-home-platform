package com.nndai.myhome.data.repository

import com.nndai.myhome.data.model.Device
import com.nndai.myhome.data.remote.SupabaseConfig
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DeviceManagerRepository {
    private val supabaseDb = SupabaseConfig.client.postgrest
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    suspend fun fetchDevices(): Result<Unit> {
        return try {
            _lastError.value = null
            val result = supabaseDb.from("devices")
                .select(Columns.list("id", "device_id", "profile", "name", "owner_id", "status"))
                .decodeList<Device>()
            _devices.value = result
            Result.success(Unit)
        } catch (e: Exception) {
            e.printStackTrace()
            _lastError.value = "Couldn't load devices: ${e.message}"
            Result.failure(e)
        }
    }

    suspend fun addDevice(
        name: String,
        profile: String,
        deviceId: String,
        controlKeyHex: String? = null
    ): Result<Device> {
        return try {
            val params = buildJsonObject {
                put("p_device_id", deviceId)
                put("p_profile", profile)
                put("p_name", name)
                controlKeyHex?.let { hex ->
                    hexToBase64(hex)?.let { base64 ->
                        put("p_control_key", base64)
                    }
                }
            }
            val device: Device = supabaseDb.rpc("claim_device", params).decodeSingle()
            _lastError.value = null
            fetchDevices()
            Result.success(device)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private fun hexToBase64(hex: String): String? {
        if (hex.length % 2 != 0) return null
        return runCatching {
            val bytes = ByteArray(hex.length / 2)
            for (i in bytes.indices) {
                bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }.getOrNull()
    }
}
