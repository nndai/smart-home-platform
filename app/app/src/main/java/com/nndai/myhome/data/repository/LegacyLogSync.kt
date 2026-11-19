package com.nndai.myhome.data.repository

import android.content.SharedPreferences
import com.nndai.myhome.data.remote.PumpCommandDataSource
import com.nndai.myhome.data.remote.PumpCommandEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Read-only v1 import. One page or chunk is in flight for this device at a time. */
class LegacyLogSync(private val remote: PumpCommandDataSource, private val prefs: SharedPreferences) {
    suspend fun sync(streams: List<Int>) {
        for (stream in streams) {
            val directory = "/logs/${listOf("power", "toggle", "sys")[stream]}/"
            var page = 0
            do {
                val result = coroutineScope {
                    val id = remote.generateReqId()
                    val reply = async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(10_000) { remote.events.filterIsInstance<PumpCommandEvent.ListDirResult>().first { it.reqId == id } }
                    }
                    remote.listDir(directory, page.toLong(), 20, id)
                    reply.await()
                }
                check(result.success) { result.message ?: "Không thể nhập lịch sử cũ" }
                require(result.path == directory && result.entries.size <= 20 && page <= 4096)
                for (file in result.entries) {
                    if (file.type == "file" && (file.name == "nosync.log" || file.name.matches(Regex("\\d{2}-\\d{2}-\\d{4}\\.log")))) {
                        read(directory + file.name, file.size)
                    }
                }
                page += 20
            } while (result.more)
        }
    }

    private suspend fun read(path: String, target: Long) {
        require(target in 0..2_000_000)
        val saved = prefs.getString("raw_content_$path", "").orEmpty().toByteArray(Charsets.UTF_8)
        val size = prefs.getLong("raw_size_$path", -1)
        val output = java.io.ByteArrayOutputStream()
        if (size == saved.size.toLong() && size <= target) output.write(saved)
        while (output.size() < target) {
            val offset = output.size().toLong()
            val limit = minOf(768L, target - offset)
            val result = coroutineScope {
                val id = remote.generateReqId()
                val reply = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(10_000) { remote.events.filterIsInstance<PumpCommandEvent.ReadFileResult>().first { it.reqId == id } }
                }
                remote.readFile(path, offset, limit, false, id)
                reply.await()
            }
            check(result.success && result.path == path && result.offset == offset &&
                result.data.isNotEmpty() && result.data.size <= limit) { "Không thể đọc lịch sử cũ tại offset $offset" }
            output.write(result.data)
        }
        check(prefs.edit().putString("raw_content_$path", output.toString("UTF-8"))
            .putLong("raw_size_$path", output.size().toLong()).commit()) { "Không thể lưu lịch sử cũ" }
    }
}
