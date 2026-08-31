package com.nndai.myhome.data.model

import kotlinx.serialization.Serializable

/**
 * Model representing a smart home device registered in Supabase and cached locally.
 */
@Serializable
data class Device(
    val id: String,
    val device_id: String, // "dev-xxx"
    val name: String,
    val profile: String, // "pump", "switch", "fan"
    val status: String? = null,
    val owner_id: String? = null,
    val role: String? = null, // "OWNER", "ADMIN", "MEMBER", "VIEWER", "TRANSFERRED"
    val control_key: String? = null // Base64 encoded from Supabase
) {
    /**
     * Checks if this device ownership has been transferred away from the
     * current account (old owner after a re-pair overwrite).
     *
     * Legitimate shared members (ADMIN/MEMBER/VIEWER with a role row) are NOT
     * transferred even though owner_id points to someone else. The plain
     * owner_id comparison is only a fallback for legacy rows without role.
     */
    fun isTransferred(currentUserId: String?): Boolean {
        if (role?.equals("TRANSFERRED", ignoreCase = true) == true) return true
        if (!role.isNullOrBlank()) return false
        return owner_id != null && currentUserId != null && owner_id != currentUserId
    }
}

@Serializable
data class DeviceControlKey(
    val device_id: String,
    val control_key: String?
)
