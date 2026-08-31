package com.nndai.myhome.data.repository

import android.util.Log
import com.nndai.myhome.data.model.ActiveInvite
import com.nndai.myhome.data.model.CreatedInvite
import com.nndai.myhome.data.model.DeviceMember
import com.nndai.myhome.data.model.RedeemedDevice
import com.nndai.myhome.data.remote.SupabaseConfig
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/**
 * Repository for device sharing: invites (create/redeem/list/revoke) and
 * member management (list/update role/remove/leave).
 *
 * All writes go through SECURITY DEFINER RPCs in 0008_sharing.sql which
 * enforce the permission matrix server-side; this class only maps errors
 * to user-friendly Vietnamese messages.
 */
class DeviceShareRepository {

    private val supabaseDb = SupabaseConfig.client.postgrest

    // ── Invites ─────────────────────────────────────────────────────────────

    /** Owner/Admin tạo mã invite cho thiết bị. Role: ADMIN/MEMBER/VIEWER. */
    suspend fun createInvite(deviceUuid: String, role: String): Result<CreatedInvite> {
        return try {
            val params = buildJsonObject {
                put("p_device_id", deviceUuid)
                put("p_role", role)
            }
            val invite = supabaseDb.rpc("create_invite", params).decodeSingle<CreatedInvite>()
            Result.success(invite)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "createInvite() failed: ${e.message}")
            Result.failure(mapError(e, "Không thể tạo mã chia sẻ"))
        }
    }

    /** Nhập mã invite → tham gia thiết bị. Idempotent nếu đã là member. */
    suspend fun redeemInvite(code: String): Result<RedeemedDevice> {
        return try {
            val params = buildJsonObject { put("p_code", code.trim()) }
            val device = supabaseDb.rpc("redeem_invite", params).decodeSingle<RedeemedDevice>()
            Result.success(device)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "redeemInvite() failed: ${e.message}")
            val msg = e.message ?: ""
            Result.failure(
                when {
                    msg.contains("invalid_code", true) -> IllegalArgumentException("Mã chia sẻ không hợp lệ")
                    msg.contains("invite_expired", true) -> IllegalStateException("Mã chia sẻ đã hết hạn")
                    else -> mapError(e, "Không thể nhập mã chia sẻ")
                }
            )
        }
    }

    /** Danh sách mã đang hoạt động của một thiết bị. */
    suspend fun listInvites(deviceUuid: String): Result<List<ActiveInvite>> {
        return try {
            val params = buildJsonObject { put("p_device_id", deviceUuid) }
            val invites = supabaseDb.rpc("list_invites", params).decodeList<ActiveInvite>()
            Result.success(invites)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "listInvites() failed: ${e.message}")
            Result.failure(mapError(e, "Không thể tải danh sách mã"))
        }
    }

    /** Thu hồi một mã chưa dùng. */
    suspend fun revokeInvite(code: String): Result<Unit> {
        return try {
            val params = buildJsonObject { put("p_code", code) }
            supabaseDb.rpc("revoke_invite", params)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "revokeInvite() failed: ${e.message}")
            Result.failure(mapError(e, "Không thể thu hồi mã"))
        }
    }

    // ── Members ─────────────────────────────────────────────────────────────

    /** Danh sách thành viên của thiết bị (kèm email từ profiles). */
    suspend fun listMembers(deviceUuid: String): Result<List<DeviceMember>> {
        return try {
            val params = buildJsonObject { put("p_device_id", deviceUuid) }
            val members = supabaseDb.rpc("list_device_members", params).decodeList<DeviceMember>()
            Result.success(members)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "listMembers() failed: ${e.message}")
            Result.failure(mapError(e, "Không thể tải danh sách thành viên"))
        }
    }

    /** Đổi vai trò một thành viên (theo ma trận quyền ở RPC). */
    suspend fun updateMemberRole(deviceUuid: String, userId: String, newRole: String): Result<Unit> {
        return try {
            val params = buildJsonObject {
                put("p_device_id", deviceUuid)
                put("p_user_id", userId)
                put("p_new_role", newRole)
            }
            supabaseDb.rpc("update_member_role", params)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "updateMemberRole() failed: ${e.message}")
            Result.failure(mapError(e, "Không thể đổi vai trò"))
        }
    }

    /** Xóa một thành viên khỏi thiết bị (OWNER/ADMIN). */
    suspend fun removeMember(deviceUuid: String, userId: String): Result<Unit> {
        return try {
            val params = buildJsonObject {
                put("p_device_id", deviceUuid)
                put("p_user_id", userId)
            }
            supabaseDb.rpc("remove_member", params)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "removeMember() failed: ${e.message}")
            Result.failure(mapError(e, "Không thể xóa thành viên"))
        }
    }

    /** Tự rời khỏi thiết bị (non-owner). */
    suspend fun leaveDevice(deviceUuid: String): Result<Unit> {
        return try {
            val params = buildJsonObject { put("p_device_id", deviceUuid) }
            supabaseDb.rpc("leave_device", params)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "leaveDevice() failed: ${e.message}")
            val msg = e.message ?: ""
            Result.failure(
                when {
                    msg.contains("owner_cannot_leave", true) ->
                        IllegalStateException("Chủ sở hữu không thể rời — hãy xóa thiết bị hoặc chuyển nhượng")
                    else -> mapError(e, "Không thể rời thiết bị")
                }
            )
        }
    }

    /**
     * Map Postgres RPC exception messages (raised by 0008_sharing.sql) to
     * friendly messages; network errors pass through as-is.
     */
    private fun mapError(e: Throwable, fallback: String): Throwable {
        var t: Throwable? = e
        while (t != null) {
            if (t is IOException) return IOException("Lỗi kết nối mạng", e)
            t = t.cause
        }
        val raw = e.message ?: ""
        return when {
            raw.contains("permission_denied", true) -> SecurityException("Bạn không có quyền thực hiện hành động này")
            raw.contains("cannot_modify_owner", true) -> SecurityException("Không thể thay đổi chủ sở hữu")
            raw.contains("cannot_remove_owner", true) -> SecurityException("Không thể xóa chủ sở hữu")
            raw.contains("member_not_found", true) -> IllegalArgumentException("Thành viên không tồn tại")
            raw.contains("device_not_found", true) -> IllegalArgumentException("Thiết bị không tồn tại")
            raw.contains("invalid_role", true) -> IllegalArgumentException("Vai trò không hợp lệ")
            else -> RuntimeException(raw.ifBlank { fallback }, e)
        }
    }

    companion object {
        private const val TAG = "DeviceShare"
    }
}
