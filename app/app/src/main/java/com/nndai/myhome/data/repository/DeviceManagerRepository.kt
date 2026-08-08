package com.nndai.myhome.data.repository

import com.nndai.myhome.data.model.Device
import com.nndai.myhome.data.remote.SupabaseConfig
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DeviceManagerRepository {
    private val supabaseDb = SupabaseConfig.client.postgrest
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    suspend fun fetchDevices() {
        try {
            val result = supabaseDb.from("devices").select().decodeList<Device>()
            _devices.value = result
        } catch (e: Exception) {
            e.printStackTrace()
            // Keep existing devices or handle error
        }
    }

    suspend fun addDevice(name: String, profile: String, deviceId: String) {
        try {
            val params = buildJsonObject {
                put("p_device_id", deviceId)
                put("p_profile", profile)
                put("p_name", name)
                // control_key is null for mock pairing, would be bytea string in real flow
            }
            // Execute RPC call to claim_device
            supabaseDb.rpc("claim_device", params)
            // Refresh devices list
            fetchDevices()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
