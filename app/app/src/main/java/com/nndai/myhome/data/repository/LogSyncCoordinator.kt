package com.nndai.myhome.data.repository

import com.nndai.myhome.data.local.SegmentLogCache
import com.nndai.myhome.data.remote.PumpCommandDataSource
import com.nndai.myhome.data.remote.PumpCommandEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Sequential requests keep flash/MQTT load bounded and make every response attributable. */
class LogSyncCoordinator(
    private val remote: PumpCommandDataSource,
    private val cache: SegmentLogCache,
    private val onGap: suspend () -> Unit
) {
    suspend fun request(command: String, payload: JSONObject = JSONObject()): JSONObject = coroutineScope {
        val id = remote.generateReqId()
        // Subscribe before sending: a local WebSocket may respond before send() returns.
        val response = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(10_000) {
                remote.events.filterIsInstance<PumpCommandEvent.LogStorageResponse>()
                    .first { it.command == command && it.json.optString("reqId") == id }.json
            }
        }
        remote.logCommand(command, payload, id)
        response.await()
    }

    private suspend fun retryRequest(command: String, payload: JSONObject = JSONObject()): JSONObject {
        repeat(5) { attempt ->
            val result = request(command, payload)
            if (result.optString("status") !in listOf("busy", "recovering")) return result
            delay(250L shl attempt)
        }
        error("Thiết bị đang phục hồi hoặc bận lưu log. Vui lòng thử lại.")
    }

    /** Returns false only for an explicit old-firmware response, never for a timeout. */
    suspend fun sync(streams: List<Int>, force: Boolean): Boolean {
        val state = retryRequest("getLogState")
        if (state.optInt("logSchema") != 2) {
            if (state.optString("message").contains("Unknown command", ignoreCase = true)) return false
            error("Thiết bị không trả về khả năng đồng bộ log hợp lệ")
        }
        check(state.optString("status") == "ok")
        val entries = state.getJSONArray("entries")
        for (stream in streams) {
            val snapshot = (0 until entries.length()).map { entries.getJSONObject(it) }
                .first { it.getInt("stream") == stream }
            syncStream(stream, snapshot, force)
        }
        return true
    }

    private suspend fun syncStream(stream: Int, snapshot: JSONObject, force: Boolean) {
        val generation = snapshot.getLong("generation")
        val boot = snapshot.getLong("bootId")
        val revision = snapshot.getLong("revision")
        val target = snapshot.getLong("segmentId")
        val size = snapshot.getLong("size")
        val token = "$generation:$boot:$revision"
        if (!force && cache.token(stream) == token) return
        if (cache.cursor(stream)?.generation != generation) cache.resetCursor(stream, generation)
        if (target == 0L) { cache.markSynced(stream, generation, token); return }
        var restarts = 0
        while (true) {
            val cursor = cache.cursor(stream)!!
            val payload = JSONObject().apply {
                put("stream", stream); put("generation", generation); put("bootId", boot)
                put("segmentId", cursor.segment); put("offset", cursor.offset)
                put("snapshotId", target); put("snapshotSize", size); put("limit", 768)
            }
            val result = retryRequest("syncLog", payload)
            if (result.optString("status") in listOf("ok", "up_to_date")) {
                require(result.getInt("stream") == stream && result.getLong("generation") == generation &&
                    result.getLong("bootId") == boot) { "Log response identity mismatch" }
            }
            when (result.optString("status")) {
                "up_to_date" -> { cache.markSynced(stream, generation, token); return }
                "cursor_expired", "invalid_cursor" -> {
                    check(restarts++ == 0) { "Lịch sử thay đổi trong lúc tải, hãy đồng bộ lại" }
                    onGap(); cache.resetCursor(stream, generation)
                }
                "snapshot_expired", "generation_changed" -> error("Thiết bị khởi động lại hoặc kho log đã thay đổi. Hãy đồng bộ lại.")
                "ok" -> {
                    val id = result.getLong("segmentId")
                    val offset = result.getLong("offset")
                    val data = result.opt("data") as? ByteArray ?: error("Missing binary log data")
                    require(result.getLong("generation") == generation && result.getLong("bootId") == boot &&
                        result.getLong("snapshotId") == target && result.getLong("snapshotSize") == size)
                    require(id in 1..target && (id == cursor.segment && offset == cursor.offset ||
                        id > cursor.segment && offset == 0L || cursor.segment == 0L && offset == 0L))
                    require(result.getLong("nextOffset") == offset + data.size && data.isNotEmpty())
                    val more = result.getBoolean("more")
                    require(id != target || offset + data.size <= size)
                    require(more || id == target && offset + data.size == size) { "Incomplete snapshot response" }
                    cache.append(stream, generation, id, offset, data, complete = result.optBoolean("sealed") || !more)
                    if (!more) { cache.markSynced(stream, generation, token); return }
                }
                else -> error("Không thể đọc log: ${result.optString("status")}")
            }
        }
    }
}
