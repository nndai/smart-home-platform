package com.nndai.myhome.data.repository

import android.content.Context
import android.util.Log
import com.nndai.myhome.data.model.Device
import com.nndai.myhome.data.pairing.PendingClaim
import com.nndai.myhome.data.pairing.PendingClaimStore
import com.nndai.myhome.data.remote.SupabaseConfig
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

sealed class ClaimError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class AlreadyClaimed(val deviceId: String, cause: Throwable? = null) :
        ClaimError("Thiết bị ($deviceId) đã được liên kết với một tài khoản khác", cause)
    class InvalidControlKey(val deviceId: String, cause: Throwable? = null) :
        ClaimError("Khóa điều khiển thiết bị không hợp lệ", cause)
    class NetworkError(message: String, cause: Throwable? = null) :
        ClaimError(message, cause)
    class Unknown(message: String, cause: Throwable? = null) :
        ClaimError(message, cause)
}

class DeviceManagerRepository(context: Context) {
    private val supabaseDb = SupabaseConfig.client.postgrest
    private val pendingStore = PendingClaimStore(context)
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    suspend fun fetchDevices(): Result<Unit> {
        syncPendingClaims()
        return fetchDevicesInternal()
    }

    private suspend fun fetchDevicesInternal(): Result<Unit> {
        return try {
            _lastError.value = null
            val result = supabaseDb.from("devices")
                .select(Columns.list("id", "device_id", "profile", "name", "owner_id", "status"))
                .decodeList<Device>()
            _devices.value = result
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "fetchDevicesInternal() failed: ${e.message}")
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
        val r = claimOnce(name, profile, deviceId, controlKeyHex)
        if (r.isSuccess) {
            pendingStore.remove(deviceId)
            fetchDevicesInternal()
            return r
        }

        val err = r.exceptionOrNull()
        if (err is ClaimError.AlreadyClaimed) {
            // Check if current user already owns this device
            fetchDevicesInternal()
            val existing = _devices.value.find { it.device_id == deviceId }
            if (existing != null) {
                Log.d(TAG, "addDevice(): Device $deviceId is already claimed by current user. Returning existing device.")
                pendingStore.remove(deviceId)
                _lastError.value = null
                return Result.success(existing)
            } else {
                Log.w(TAG, "addDevice(): Device $deviceId claimed by another user. Removing from pendingStore.")
                pendingStore.remove(deviceId)
                _lastError.value = "Thiết bị đã thuộc về một tài khoản khác"
                return Result.failure(err)
            }
        }

        if (err != null && !isTransientNetworkError(err)) {
            // Permanent failure (e.g. invalid control key) -> remove from pending store
            Log.w(TAG, "addDevice(): Permanent failure for $deviceId: ${err.message}. Removing from pendingStore.")
            pendingStore.remove(deviceId)
        }

        return r
    }

    private suspend fun claimOnce(
        name: String,
        profile: String,
        deviceId: String,
        controlKeyHex: String?
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
            val response = supabaseDb.rpc("claim_device", params)
            val device: Device = runCatching {
                response.decodeAs<Device>()
            }.getOrElse {
                response.decodeSingle<Device>()
            }
            _lastError.value = null
            Result.success(device)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val parsedError = parseClaimError(deviceId, e)
            Log.e(TAG, "claimOnce() failed for $deviceId: ${parsedError.message}")
            _lastError.value = parsedError.message
            Result.failure(parsedError)
        }
    }

    private fun parseClaimError(deviceId: String, e: Throwable): ClaimError {
        val msg = e.message ?: ""
        return when {
            msg.contains("device_already_claimed", ignoreCase = true) -> ClaimError.AlreadyClaimed(deviceId, e)
            msg.contains("invalid_control_key", ignoreCase = true) -> ClaimError.InvalidControlKey(deviceId, e)
            isTransientNetworkError(e) -> ClaimError.NetworkError("Lỗi kết nối mạng", e)
            else -> ClaimError.Unknown(msg.ifBlank { "Không thể đăng ký thiết bị" }, e)
        }
    }

    /**
     * Synchronize pending claims stored offline.
     * Retries transient network errors up to 3 times per run.
     * Automatically removes claims that succeed or encounter permanent errors (such as device_already_claimed).
     */
    suspend fun syncPendingClaims(): Int {
        val pending = pendingStore.getAll()
        if (pending.isEmpty()) return 0
        Log.d(TAG, "syncPendingClaims(): Found ${pending.size} pending claim(s)")
        var synced = 0

        fetchDevicesInternal()

        for (p in pending) {
            // If device is already in current user's list, remove pending claim
            if (_devices.value.any { it.device_id == p.deviceId }) {
                Log.d(TAG, "syncPendingClaims(): Device ${p.deviceId} already owned by user. Removing pending claim.")
                pendingStore.remove(p.deviceId)
                synced++
                continue
            }

            var done = false
            var isPermanentError = false

            repeat(3) { attempt ->
                val r = claimOnce(p.name, p.profile, p.deviceId, p.controlKeyHex)
                if (r.isSuccess) {
                    done = true
                    synced++
                    return@repeat
                }

                val cause = r.exceptionOrNull()
                if (cause is ClaimError.AlreadyClaimed) {
                    fetchDevicesInternal()
                    if (_devices.value.any { it.device_id == p.deviceId }) {
                        done = true
                        synced++
                    } else {
                        isPermanentError = true
                        Log.w(TAG, "syncPendingClaims(): Device ${p.deviceId} is claimed by another user. Removing pending claim.")
                    }
                    return@repeat
                }

                if (!isTransientNetworkError(cause)) {
                    isPermanentError = true
                    Log.w(TAG, "syncPendingClaims(): Permanent failure for ${p.deviceId}: ${cause?.message}. Removing pending claim.")
                    return@repeat
                }

                Log.d(TAG, "syncPendingClaims(): transient error retry $attempt for ${p.deviceId}: ${cause?.message}")
                delay(1_500)
            }

            if (done || isPermanentError) {
                pendingStore.remove(p.deviceId)
            }
        }

        if (synced > 0) {
            fetchDevicesInternal()
        }
        return synced
    }

    companion object {
        private const val TAG = "DeviceManager"

        /** Transients network errors (e.g. IOException) are retried; permanent errors are not. */
        fun isTransientNetworkError(e: Throwable?): Boolean {
            var t: Throwable? = e
            while (t != null) {
                if (t is IOException) return true
                t = t.cause
            }
            return false
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
