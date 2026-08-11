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
    val role: String? = null // "OWNER", "ADMIN", "MEMBER", "VIEWER", "TRANSFERRED"
) {
    /**
     * Checks if this device ownership has been transferred to a new user account.
     */
    fun isTransferred(currentUserId: String?): Boolean {
        if (role?.equals("TRANSFERRED", ignoreCase = true) == true) return true
        if (owner_id != null && currentUserId != null && owner_id != currentUserId) return true
        return false
    }
}
