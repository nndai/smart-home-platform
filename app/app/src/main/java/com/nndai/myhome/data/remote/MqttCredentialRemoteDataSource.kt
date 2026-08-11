package com.nndai.myhome.data.remote

import android.util.Log
import com.nndai.myhome.data.model.MqttCredential
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Remote Data Source to fetch MQTT Credentials from Supabase RPC `get_mqtt_credential`.
 */
class MqttCredentialRemoteDataSource {

    suspend fun fetchMqttCredential(): Result<MqttCredential> {
        return try {
            val response = SupabaseConfig.client.postgrest.rpc("get_mqtt_credential")
            val res: JsonObject = runCatching {
                response.decodeAs<JsonObject>()
            }.getOrElse {
                response.decodeSingle<JsonObject>()
            }

            val username = res["username"]?.jsonPrimitive?.content ?: ""
            val password = res["password"]?.jsonPrimitive?.content ?: ""

            val credential = MqttCredential(username, password)
            if (credential.isValid()) {
                Result.success(credential)
            } else {
                Result.failure(IllegalStateException("Returned empty MQTT credentials from Supabase RPC"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchMqttCredential() failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "MqttCredentialRemoteDS"
    }
}
