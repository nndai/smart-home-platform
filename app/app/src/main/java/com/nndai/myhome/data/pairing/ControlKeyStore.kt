package com.nndai.myhome.data.pairing

import android.content.Context
import java.security.SecureRandom

class ControlKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pairing_store", Context.MODE_PRIVATE)

    fun save(deviceId: String, keyHex: String) {
        prefs.edit().putString(deviceId, keyHex).apply()
    }

    fun get(deviceId: String): String? = prefs.getString(deviceId, null)

    // ── Seq counter cho envelope lệnh (persist để không bị thiết bị từ chối
    //    "stale seq" sau khi app restart — thiết bị persist last_seq) ──
    fun getSeq(deviceId: String): Long = prefs.getLong("seq_$deviceId", 0L)

    fun nextSeq(deviceId: String): Long {
        val next = getSeq(deviceId) + 1
        prefs.edit().putLong("seq_$deviceId", next).apply()
        return next
    }

    companion object {
        private val secureRandom = SecureRandom()

        fun generateHex(bytes: Int = 32): String {
            val buf = ByteArray(bytes)
            secureRandom.nextBytes(buf)
            return buf.joinToString("") { "%02x".format(it) }
        }
    }
}
