package com.nndai.myhome.data.local

import android.content.Context
import android.util.Log
import com.nndai.myhome.data.model.Device
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Manages local persistence of device lists in SharedPreferences.
 * Provides instant cold-start device list loading (<5ms) for optimal user experience.
 */
class LocalDeviceCache(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val jsonFormatter = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Retrieves the cached device list instantly from local storage.
     * Returns an empty list if no cache is available or if parsing fails.
     */
    fun getCachedDevices(): List<Device> {
        val rawJson = prefs.getString(KEY_DEVICE_LIST, null) ?: return emptyList()
        return try {
            jsonFormatter.decodeFromString<List<Device>>(rawJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode cached device list: ${e.message}")
            emptyList()
        }
    }

    /**
     * Persists the given device list into local storage for future instant boots.
     */
    fun saveCachedDevices(devices: List<Device>) {
        try {
            val jsonString = jsonFormatter.encodeToString(devices)
            prefs.edit().putString(KEY_DEVICE_LIST, jsonString).apply()
            Log.d(TAG, "Saved ${devices.size} device(s) to local cache.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save device list to local cache: ${e.message}")
        }
    }

    /**
     * Clears all cached devices from local storage.
     */
    fun clearCache() {
        prefs.edit().remove(KEY_DEVICE_LIST).apply()
        Log.d(TAG, "Cleared local device cache.")
    }

    companion object {
        private const val TAG = "LocalDeviceCache"
        private const val PREFS_NAME = "myhome_device_cache"
        private const val KEY_DEVICE_LIST = "cached_devices"
    }
}
