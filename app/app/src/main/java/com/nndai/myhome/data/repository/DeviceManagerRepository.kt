package com.nndai.myhome.data.repository

import android.content.Context
import android.util.Log
import com.nndai.myhome.data.model.Device
import com.nndai.myhome.data.model.DeviceControlKey
import com.nndai.myhome.data.pairing.PendingClaim
import com.nndai.myhome.data.pairing.PendingClaimStore
import com.nndai.myhome.data.remote.SupabaseConfig
import io.github.jan.supabase.postgrest.postgrest
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

class DeviceManagerRepository(private val context: Context) {
    private val supabaseDb = SupabaseConfig.client.postgrest
    private val pendingStore = PendingClaimStore(context)
    private val localCache = com.nndai.myhome.data.local.LocalDeviceCache(context)

    // Load cached devices instantly (<5ms) on cold start
    private val _devices = MutableStateFlow<List<Device>>(localCache.getCachedDevices())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    init {
        val cached = localCache.getCachedDevices()
        if (cached.isNotEmpty()) {
            Log.d(TAG, "DeviceManagerRepository init: Loaded ${cached.size} device(s) from local cache.")
        }
    }

    /**
     * Clears user-scoped local state: cached device list + in-memory flow.
     * Called when the session ends (logout / token revoked). Device logs are
     * stored separately (LogManager) and intentionally kept across accounts.
     */
    fun clearLocalState() {
        localCache.clearCache()
        _devices.value = emptyList()
    }

    suspend fun fetchDevices(): Result<Unit> {
        syncPendingClaims()
        return fetchDevicesInternal()
    }

    private suspend fun fetchDevicesInternal(): Result<Unit> {
        return try {
            val oldList = _devices.value
            // get_my_devices (0008_sharing.sql): devices + vai trò của chính mình
            // (role) — dùng cho badge và phân quyền UI. Decode thẳng vào Device.
            val result = supabaseDb.rpc("get_my_devices").decodeList<Device>()

            // Fetch control keys securely via RPC (bypassing RLS read restrictions on the devices table)
            try {
                val keysResponse = supabaseDb.rpc("get_device_control_keys").decodeList<DeviceControlKey>()
                val keyStore = com.nndai.myhome.data.pairing.ControlKeyStore(context)
                keysResponse.forEach { item ->
                    item.control_key?.let { hexKey ->
                        // The RPC now returns the control_key directly as Hex
                        keyStore.save(item.device_id, hexKey)
                    }
                }
                // Prune stale keys: a device no longer in the authorized set
                // (membership revoked, device removed) must not keep its local
                // key — otherwise an ex-member could still sign commands.
                val validIds = keysResponse.map { it.device_id }.toSet()
                var pruned = 0
                keyStore.storedIds().filter { it !in validIds }.forEach {
                    keyStore.remove(it); pruned++
                }
                Log.i(TAG, "Control keys synced: ${keysResponse.size} from server, $pruned pruned locally.")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync control keys via RPC: ${e.message}")
            }

            // Diffing: clean up unregistered devices from HandshakeManager
            val oldIds = oldList.map { it.device_id }.toSet()
            val newIds = result.map { it.device_id }.toSet()
            val removedIds = oldIds - newIds

            val handshakeMgr = com.nndai.myhome.data.di.PumpRepositoryProvider.provideDeviceHandshakeManager()
            removedIds.forEach { id ->
                Log.d(TAG, "Device $id removed remotely. Unregistering from HandshakeManager...")
                handshakeMgr.unregisterDevice(id)
            }

            _devices.value = result
            // Update local cache for future fast boots
            localCache.saveCachedDevices(result)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "fetchDevicesInternal() failed: ${e.message}")
            _lastError.value = "Couldn't load devices: ${e.message}"
            Result.failure(e)
        }
    }

    /**
     * Claim thiết bị lên Supabase. DB tự ghi đè nếu thiết bị đã tồn tại
     * (cập nhật owner_id, control_key, device_members).
     */
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
        if (err != null && !isTransientNetworkError(err)) {
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
                    put("p_control_key", "\\x$hex")
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
     * DB tự ghi đè nếu thiết bị đã có chủ → không cần xử lý AlreadyClaimed.
     */
    suspend fun syncPendingClaims(): Int {
        val pending = pendingStore.getAll()
        if (pending.isEmpty()) return 0
        Log.d(TAG, "syncPendingClaims(): Found ${pending.size} pending claim(s)")
        var synced = 0

        fetchDevicesInternal()

        for (p in pending) {
            // Thiết bị đã nằm trong danh sách của user → bỏ qua
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

    /**
     * Cập nhật tên thiết bị trong Supabase DB và cập nhật local cache.
     */
    suspend fun updateDeviceName(deviceId: String, newName: String): Result<Unit> {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("Tên thiết bị không được để trống"))
        }
        return try {
            supabaseDb.from("devices").update(
                {
                    set("name", trimmed)
                }
            ) {
                filter {
                    eq("device_id", deviceId)
                }
            }

            // Cập nhật StateFlow và local cache
            _devices.value = _devices.value.map {
                if (it.device_id == deviceId) it.copy(name = trimmed) else it
            }
            localCache.saveCachedDevices(_devices.value)
            _lastError.value = null
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "updateDeviceName() failed for $deviceId: ${e.message}")
            _lastError.value = "Không thể đổi tên thiết bị: ${e.message}"
            Result.failure(e)
        }
    }

    /**
     * Xóa liên kết thiết bị cho user hiện tại.
     * DB function remove_device:
     *   - Xóa dòng device_members của user.
     *   - Nếu user là owner → xóa luôn thiết bị (cascade).
     *   - Nếu user là TRANSFERRED → chỉ xóa dòng member (dọn rác).
     */
    suspend fun removeDevice(deviceId: String): Result<Unit> {
        return try {
            val params = buildJsonObject {
                put("p_device_id", deviceId)
            }
            supabaseDb.rpc("remove_device", params)
            pendingStore.remove(deviceId)

            // Dọn dẹp key trong ControlKeyStore
            val keyStore = com.nndai.myhome.data.pairing.ControlKeyStore(context)
            keyStore.remove(deviceId)

            // Dọn dẹp trong HandshakeManager
            val handshakeMgr = com.nndai.myhome.data.di.PumpRepositoryProvider.provideDeviceHandshakeManager()
            handshakeMgr.unregisterDevice(deviceId)

            fetchDevicesInternal()
            _lastError.value = null
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "removeDevice() failed for $deviceId: ${e.message}")
            _lastError.value = "Không thể xóa thiết bị: ${e.message}"
            Result.failure(e)
        }
    }

    /**
     * Rời khỏi một thiết bị được chia sẻ (member/viewer tự rời — RPC
     * leave_device trong 0008_sharing.sql). Dọn key + handshake cục bộ,
     * rồi làm mới danh sách.
     */
    suspend fun leaveSharedDevice(deviceId: String, deviceUuid: String): Result<Unit> {
        val shareRepo = com.nndai.myhome.data.di.PumpRepositoryProvider.provideDeviceShareRepository()
        val result = shareRepo.leaveDevice(deviceUuid)
        if (result.isSuccess) {
            pendingStore.remove(deviceId)
            com.nndai.myhome.data.pairing.ControlKeyStore(context).remove(deviceId)
            val handshakeMgr = com.nndai.myhome.data.di.PumpRepositoryProvider.provideDeviceHandshakeManager()
            handshakeMgr.unregisterDevice(deviceId)
            fetchDevicesInternal()
        }
        return result
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

    private fun base64ToHex(base64: String): String? {
        return runCatching {
            val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            bytes.joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }
}
