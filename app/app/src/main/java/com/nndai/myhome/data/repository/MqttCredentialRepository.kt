package com.nndai.myhome.data.repository

import android.util.Log
import com.nndai.myhome.data.local.MqttKeystoreStorage
import com.nndai.myhome.data.model.MqttCredential
import com.nndai.myhome.data.remote.MqttCredentialRemoteDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface CredentialSyncResult {
    data class Unchanged(val credential: MqttCredential) : CredentialSyncResult
    data class Updated(val newCredential: MqttCredential) : CredentialSyncResult
    data class Failed(val cause: Throwable) : CredentialSyncResult
}

/**
 * Repository orchestrating local Android Keystore storage and remote Supabase RPC credential retrieval.
 */
class MqttCredentialRepository(
    private val keystoreStorage: MqttKeystoreStorage,
    private val remoteDataSource: MqttCredentialRemoteDataSource
) {
    private val _currentCredential = MutableStateFlow<MqttCredential?>(null)
    val currentCredential: StateFlow<MqttCredential?> = _currentCredential.asStateFlow()

    init {
        // Fast load from Android Keystore on instantiation
        val cached = keystoreStorage.getCredential()
        _currentCredential.value = cached
        Log.d(TAG, "init: Cached credential from Keystore is ${if (cached != null) "PRESENT" else "ABSENT"}")
    }

    /**
     * Instantly get cached credentials from Android Keystore.
     */
    fun getCachedCredential(): MqttCredential? {
        val cached = keystoreStorage.getCredential()
        _currentCredential.value = cached
        return cached
    }

    /**
     * Synchronize remote credentials from Supabase RPC with Android Keystore.
     *
     * Compares remote credential with cached Keystore credential:
     * - If different or local is empty: updates Keystore & emits `CredentialSyncResult.Updated`
     * - If identical: emits `CredentialSyncResult.Unchanged`
     * - If network/RPC fails: emits `CredentialSyncResult.Failed`
     */
    suspend fun syncWithRemote(): CredentialSyncResult {
        val localCred = keystoreStorage.getCredential()
        val remoteResult = remoteDataSource.fetchMqttCredential()

        return remoteResult.fold(
            onSuccess = { remoteCred ->
                if (localCred != remoteCred) {
                    Log.d(TAG, "syncWithRemote(): Credentials updated from Supabase. Updating Keystore...")
                    keystoreStorage.saveCredential(remoteCred)
                    _currentCredential.value = remoteCred
                    CredentialSyncResult.Updated(remoteCred)
                } else {
                    Log.d(TAG, "syncWithRemote(): Remote credential matches Keystore cached credential. No update needed.")
                    CredentialSyncResult.Unchanged(remoteCred)
                }
            },
            onFailure = { error ->
                Log.w(TAG, "syncWithRemote(): Failed to fetch remote credentials: ${error.message}")
                CredentialSyncResult.Failed(error)
            }
        )
    }

    fun clearCredential() {
        keystoreStorage.clear()
        _currentCredential.value = null
    }

    companion object {
        private const val TAG = "MqttCredentialRepo"
    }
}
