package com.nndai.myhome.data.repository

import com.nndai.myhome.data.remote.SupabaseConfig
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
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

    suspend fun logout() {
        try {
            supabaseAuth.signOut()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
