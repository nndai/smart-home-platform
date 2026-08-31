package com.nndai.myhome.data.model

import kotlinx.serialization.Serializable

/**
 * A member of a shared device (row of device_members, joined with profiles).
 */
@Serializable
data class DeviceMember(
    val user_id: String,
    val email: String? = null,
    val role: String,
    val joined_at: String? = null
)

/**
 * An active invite code created by OWNER/ADMIN (no expiry, single-use).
 */
@Serializable
data class ActiveInvite(
    val code: String,
    val role: String,
    val created_at: String? = null
)

/**
 * Result returned by redeem_invite RPC — info about the device just joined.
 */
@Serializable
data class RedeemedDevice(
    val device_id: String,
    val device_name: String,
    val profile: String,
    val role: String
)

/**
 * Result of create_invite RPC.
 */
@Serializable
data class CreatedInvite(
    val code: String,
    val device_name: String
)

/**
 * Role constants + permission matrix helpers (mirror 0008_sharing.sql rules).
 */
object DeviceRoles {
    const val OWNER = "OWNER"
    const val ADMIN = "ADMIN"
    const val MEMBER = "MEMBER"
    const val VIEWER = "VIEWER"

    /** Owner/Admin quản lý thành viên + invite. */
    fun canManage(role: String?): Boolean = role == OWNER || role == ADMIN

    /** Member có controlKey nên điều khiển được; VIEWER read-only thật sự. */
    fun canControl(role: String?): Boolean = role != VIEWER && !role.isNullOrBlank()

    /** Nhãn hiển thị tiếng Việt. */
    fun label(role: String?): String = when (role?.uppercase()) {
        OWNER -> "Chủ sở hữu"
        ADMIN -> "Quản lý"
        MEMBER -> "Thành viên"
        VIEWER -> "Chỉ xem"
        else -> role ?: ""
    }
}
