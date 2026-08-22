package com.nndai.myhome.data.pairing

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Tiện ích mã hóa End-to-End (E2E) AES-256-GCM cho controlKey.
 * Dùng khi App cần gửi controlKey của target (targetKey) sang Remote Switch qua MQTT.
 *
 * Khóa mã hóa: controlKey của chính Remote Switch (32 bytes).
 * Định dạng output: Blob 64B = [ IV (16B) | Ciphertext (32B) | GCM Tag (16B) ] chuyển thành chuỗi Hex 128 ký tự.
 */
object ControlKeyCrypto {

    private val secureRandom = SecureRandom()

    /**
     * Mã hóa targetKeyHex (64 hex chars = 32 bytes) bằng remoteSwitchKeyHex (64 hex chars = 32 bytes).
     * Trả về chuỗi Hex 128 ký tự gồm IV(16B) + Ciphertext(32B) + Tag(16B).
     */
    fun encryptTargetKey(targetKeyHex: String, remoteSwitchKeyHex: String): String? {
        return runCatching {
            val targetBytes = hexToBytes(targetKeyHex) ?: return null
            val remoteKeyBytes = hexToBytes(remoteSwitchKeyHex) ?: return null

            if (targetBytes.size != 32 || remoteKeyBytes.size != 32) return null

            val iv = ByteArray(16).apply { secureRandom.nextBytes(this) }
            val secretKey = SecretKeySpec(remoteKeyBytes, "AES")
            val spec = GCMParameterSpec(128, iv) // 128-bit authentication tag

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
            val cipherTextWithTag = cipher.doFinal(targetBytes) // 32 bytes ct + 16 bytes tag = 48 bytes

            val blob = ByteArray(64)
            System.arraycopy(iv, 0, blob, 0, 16)
            System.arraycopy(cipherTextWithTag, 0, blob, 16, 48)

            bytesToHex(blob)
        }.getOrNull()
    }

    /**
     * Giải mã E2E (phục vụ test hoặc xác thực cục bộ).
     */
    fun decryptTargetKey(encryptedHex: String, remoteSwitchKeyHex: String): String? {
        return runCatching {
            val blob = hexToBytes(encryptedHex) ?: return null
            val remoteKeyBytes = hexToBytes(remoteSwitchKeyHex) ?: return null

            if (blob.size != 64 || remoteKeyBytes.size != 32) return null

            val iv = blob.copyOfRange(0, 16)
            val cipherTextWithTag = blob.copyOfRange(16, 64)

            val secretKey = SecretKeySpec(remoteKeyBytes, "AES")
            val spec = GCMParameterSpec(128, iv)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val plainBytes = cipher.doFinal(cipherTextWithTag)

            bytesToHex(plainBytes)
        }.getOrNull()
    }

    fun hexToBytes(hex: String): ByteArray? {
        val clean = hex.trim()
        if (clean.length % 2 != 0) return null
        return ByteArray(clean.length / 2).apply {
            for (i in indices) {
                val index = i * 2
                val byteValue = clean.substring(index, index + 2).toIntOrNull(16) ?: return null
                this[i] = byteValue.toByte()
            }
        }
    }

    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
