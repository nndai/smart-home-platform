package com.nndai.myhome.data.local

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.nndai.myhome.data.model.MqttCredential
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Storage securely backing MQTT username & password using Android KeyStore (AES-256 GCM).
 */
class MqttKeystoreStorage(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
        load(null)
    }

    @Synchronized
    fun getCredential(): MqttCredential? {
        val encryptedUser = prefs.getString(KEY_USER_ENCRYPTED, null) ?: return null
        val userIv = prefs.getString(KEY_USER_IV, null) ?: return null
        val encryptedPass = prefs.getString(KEY_PASS_ENCRYPTED, null) ?: return null
        val passIv = prefs.getString(KEY_PASS_IV, null) ?: return null

        val user = decrypt(encryptedUser, userIv) ?: return null
        val pass = decrypt(encryptedPass, passIv) ?: return null

        val cred = MqttCredential(user, pass)
        return if (cred.isValid()) cred else null
    }

    @Synchronized
    fun saveCredential(credential: MqttCredential): Boolean {
        if (!credential.isValid()) return false
        return try {
            val (encryptedUser, userIv) = encrypt(credential.username) ?: return false
            val (encryptedPass, passIv) = encrypt(credential.password) ?: return false

            prefs.edit()
                .putString(KEY_USER_ENCRYPTED, encryptedUser)
                .putString(KEY_USER_IV, userIv)
                .putString(KEY_PASS_ENCRYPTED, encryptedPass)
                .putString(KEY_PASS_IV, passIv)
                .apply()
            Log.d(TAG, "saveCredential(): Successfully saved MQTT credentials to Keystore")
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveCredential() failed: ${e.message}", e)
            false
        }
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
        Log.d(TAG, "clear(): Keystore storage cleared")
    }

    private fun getSecretKey(): SecretKey {
        val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existingKey != null) {
            return existingKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )
        val keyGenSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(keyGenSpec)
        return keyGenerator.generateKey()
    }

    private fun encrypt(plainText: String): Pair<String, String>? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv

            val encryptedBase64 = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)

            Pair(encryptedBase64, ivBase64)
        } catch (e: Exception) {
            Log.e(TAG, "encrypt() failed: ${e.message}", e)
            null
        }
    }

    private fun decrypt(encryptedBase64: String, ivBase64: String): String? {
        return try {
            val encryptedBytes = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)

            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "decrypt() failed: ${e.message}", e)
            null
        }
    }

    companion object {
        private const val TAG = "MqttKeystoreStorage"
        private const val PREFS_NAME = "mqtt_keystore_secure_prefs"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "MqttSecretKeyAlias"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128

        private const val KEY_USER_ENCRYPTED = "enc_mqtt_user"
        private const val KEY_USER_IV = "iv_mqtt_user"
        private const val KEY_PASS_ENCRYPTED = "enc_mqtt_pass"
        private const val KEY_PASS_IV = "iv_mqtt_pass"
    }
}
