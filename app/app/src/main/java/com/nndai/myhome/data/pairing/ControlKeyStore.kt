package com.nndai.myhome.data.pairing

import android.content.Context
import java.security.SecureRandom

class ControlKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pairing_store", Context.MODE_PRIVATE)

    fun save(deviceId: String, keyHex: String) {
        prefs.edit().putString(deviceId, keyHex).apply()
    }

    fun get(deviceId: String): String? = prefs.getString(deviceId, null)

    // ── Seq counter cho envelope lệnh (persist theo từng thiết bị; thiết bị giữ
    //    floor seq riêng cho mỗi sender qua field "src" của envelope) ──
    fun getSeq(deviceId: String): Long = prefs.getLong("seq_$deviceId", 0L)

    fun nextSeq(deviceId: String): Long {
        val next = getSeq(deviceId) + 1
        prefs.edit().putLong("seq_$deviceId", next).apply()
        return next
    }

    // ── Sender ID ổn định của app (field "src" trong envelope) ──
    // Phải ổn định qua mọi lần mở app: thiết bị track floor seq riêng cho từng
    // sender; nếu src đổi mỗi lần launch, cửa sổ replay của app sẽ reset.
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
