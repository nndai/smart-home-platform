package com.nndai.myhome.data.repository

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.nndai.myhome.BuildConfig
import com.nndai.myhome.data.remote.SupabaseConfig
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AuthRepository {
    private val supabaseAuth = SupabaseConfig.client.auth
    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            supabaseAuth.sessionStatus.collect { status ->
                _isLoggedIn.value = status is SessionStatus.Authenticated
            }
        }
    }

    suspend fun login(email: String, pass: String): Boolean {
        return try {
            if (email.isNotBlank() && pass.isNotBlank()) {
                supabaseAuth.signInWith(Email) {
                    this.email = email
                    this.password = pass
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Authenticate with Supabase using an ID Token obtained from Google Sign-In dialog/Credential Manager
     */
    suspend fun signInWithIdToken(idToken: String): Boolean {
        return try {
            supabaseAuth.signInWith(IDToken) {
                provider = Google
                this.idToken = idToken
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Google Sign-In using Credential Manager (Bottom sheet)
     */
    suspend fun signInWithGoogle(context: Context): Boolean {
        return try {
            val credentialManager = CredentialManager.create(context)

            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(context, request)
            val credential = result.credential

            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val idToken = googleIdTokenCredential.idToken
                signInWithIdToken(idToken)
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun logout() {
        try {
            supabaseAuth.signOut()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
