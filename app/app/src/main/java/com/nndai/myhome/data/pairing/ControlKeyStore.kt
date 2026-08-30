package com.nndai.myhome.data.pairing

import android.content.Context
import java.security.SecureRandom

class ControlKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pairing_store", Context.MODE_PRIVATE)

    private val keyStore: java.security.KeyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }

    private fun getSecretKey(): javax.crypto.SecretKey {
        val existingKey = keyStore.getKey("ControlKeyAlias", null) as? javax.crypto.SecretKey
        if (existingKey != null) return existingKey

        val keyGenerator = javax.crypto.KeyGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        val keyGenSpec = android.security.keystore.KeyGenParameterSpec.Builder(
            "ControlKeyAlias",
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(keyGenSpec)
        return keyGenerator.generateKey()
    }

    fun save(deviceId: String, keyHex: String) {
        try {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, getSecretKey())
            val encryptedBytes = cipher.doFinal(keyHex.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv

            val encryptedBase64 = android.util.Base64.encodeToString(encryptedBytes, android.util.Base64.NO_WRAP)
            val ivBase64 = android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP)

            prefs.edit()
                .putString("enc_$deviceId", encryptedBase64)
                .putString("iv_$deviceId", ivBase64)
                // Remove the old plaintext key if it existed
                .remove(deviceId)
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun remove(deviceId: String) {
        prefs.edit()
            .remove("enc_$deviceId")
            .remove("iv_$deviceId")
            .remove(deviceId)
            .apply()
    }

    /**
     * Removes only user-scoped secrets (control keys + their IVs).
     * Called when the session ends (logout / token revoked) so a different
     * account signing in on this device cannot reuse the previous account's
     * keys — critical for shared VIEWER accounts.
     */
    fun clearUserSecrets() {
        val editor = prefs.edit()
        secretKeys().forEach { editor.remove(it) }
        editor.apply()
    }

    /** Device ids that currently have a stored control key.
     *  IMPORTANT: derive ONLY from "enc_" entries — including "iv_" would
     *  yield phantom ids like "iv_dev-xxx", and pruning those deletes the
     *  real IV file (remove() strips prefixes per id), breaking decryption. */
    fun storedIds(): Set<String> =
        prefs.all.keys.filter { it.startsWith("enc_") }
            .map { it.removePrefix("enc_") }
            .toSet()

    private fun secretKeys(): List<String> =
        prefs.all.keys.filter { it.startsWith("enc_") || it.startsWith("iv_") }

    fun get(deviceId: String): String? {
        val encryptedBase64 = prefs.getString("enc_$deviceId", null) ?: return null
        val ivBase64 = prefs.getString("iv_$deviceId", null) ?: return null

        return try {
            val encryptedBytes = android.util.Base64.decode(encryptedBase64, android.util.Base64.NO_WRAP)
            val iv = android.util.Base64.decode(ivBase64, android.util.Base64.NO_WRAP)

            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val spec = javax.crypto.spec.GCMParameterSpec(128, iv)
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, getSecretKey(), spec)

            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ── Sender ID ổn định của app (field "src" trong envelope) ──
    fun appSenderId(): String {
        prefs.getString(KEY_APP_SENDER, null)?.let { return it }
        val id = "app-" + generateHex(8)
        prefs.edit().putString(KEY_APP_SENDER, id).apply()
        return id
    }

    companion object {
        private const val KEY_APP_SENDER = "app_sender_id"
        private val secureRandom = SecureRandom()

        fun generateHex(bytes: Int = 32): String {
            val buf = ByteArray(bytes)
            secureRandom.nextBytes(buf)
            return buf.joinToString("") { "%02x".format(it) }
        }
    }
}
