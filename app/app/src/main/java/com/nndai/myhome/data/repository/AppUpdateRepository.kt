package com.nndai.myhome.data.repository

import android.util.Log
import com.nndai.myhome.data.remote.SupabaseConfig
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.Serializable

@Serializable
data class AppVersionResponse(
    val id: String,
    val version_code: Int,
    val version_name: String,
    val download_url: String,
    val release_notes: String,
    val is_mandatory: Boolean,
    val created_at: String
)

class AppUpdateRepository {
    private val supabaseDb = SupabaseConfig.client.postgrest

    suspend fun getLatestVersion(): AppVersionResponse? {
        return try {
            val result = supabaseDb.rpc("get_latest_app_version").decodeList<AppVersionResponse>()
            result.firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get latest app version", e)
            null
        }
    }

    companion object {
        private const val TAG = "AppUpdateRepository"
    }
}
