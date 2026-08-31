package com.nndai.myhome.data.remote

import android.util.Log
import com.nndai.myhome.data.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Channel kind để phân biệt kênh nào đang active.
 */
enum class ChannelKind { WEBSOCKET, MQTT }

/**
 * Hybrid channel: khởi động tất cả kênh song song, kênh nào hoàn thành
 * handshake trước (Connected) sẽ thắng cuộc và kênh còn lại bị dừng.
 *
 * Nếu trong 10s không nhận được getStatus response từ thiết bị, handshake sẽ timeout
 * và tự động kết nối lại (Connecting).
 * Khi kênh active mất kết nối → retry lại chính kênh đó.
 */
class HybridDeviceChannel(
    private val entries: List<ChannelEntry>,
    private val scope: CoroutineScope
) : DeviceChannel {

    data class ChannelEntry(
        val kind: ChannelKind,
        val channel: DeviceChannel
    )

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()

    /** Kênh đã thắng cuộc (handshake hoàn tất). null = chưa có ai thắng. */
    @Volatile
    private var activeEntry: ChannelEntry? = null

    /** Kênh cuối cùng đã từng thắng — dùng để ưu tiên reconnect. */
    @Volatile
    private var lastActiveKind: ChannelKind? = null

    private val observeJobs = mutableListOf<Job>()

    override fun start() {
        Log.d(TAG, "start() — launching ${entries.size} channels in parallel")
        activeEntry = null
        _state.value = ConnectionState.Connecting

        entries.forEach { entry ->
            entry.channel.start()

            // Observe state changes from each child channel
            val stateJob = scope.launch {
                entry.channel.state.collect { childState ->
                    onChildStateChanged(entry, childState)
                }
            }
            observeJobs.add(stateJob)

            // Forward incoming payloads from the active channel only
            val incomingJob = scope.launch {
                entry.channel.incoming.collect { payload ->
                    if (activeEntry?.kind == entry.kind) {
                        _incoming.emit(payload)
                    }
                }
            }
            observeJobs.add(incomingJob)
        }
    }

    override fun stop() {
        Log.d(TAG, "stop()")
        observeJobs.forEach { it.cancel() }
        observeJobs.clear()
        activeEntry = null
        entries.forEach { it.channel.stop() }
        _state.value = ConnectionState.Disconnected("stopped")
    }

    override suspend fun send(raw: ByteArray): Boolean {
        val active = activeEntry ?: return false
        return active.channel.send(raw)
    }

    /**
     * Core logic: xử lý state thay đổi của từng kênh con.
     */
    private fun onChildStateChanged(entry: ChannelEntry, childState: ConnectionState) {
        Log.d(TAG, "channel ${entry.kind} → $childState")

        when (childState) {
            // ── Transport layer connected (broker / WS opened), chưa handshake ──
            is ConnectionState.TransportReady -> {
                if (activeEntry == null || activeEntry?.kind == entry.kind) {
                    // Forward TransportReady to UI so user sees "Connected to MQTT, handshaking..."
                    _state.value = childState
                }
            }

            // ── Handshake complete — this channel wins the race ──
            is ConnectionState.Connected -> {
                if (activeEntry == null) {
                    Log.d(TAG, "★ channel ${entry.kind} won the race — handshake complete")
                    activeEntry = entry
                    lastActiveKind = entry.kind
                    _state.value = childState

                    // Stop losing channels to save resources
                    entries.filter { it.kind != entry.kind }.forEach { loser ->
                        Log.d(TAG, "stopping loser channel ${loser.kind}")
                        loser.channel.stop()
                    }
                } else if (activeEntry?.kind == entry.kind) {
                    // Reconnected on the same channel — update state
                    _state.value = childState
                }
            }

            // ── Active or reconnecting channel lost connection / timeout ──
            is ConnectionState.Disconnected, is ConnectionState.Failed -> {
                if (activeEntry?.kind == entry.kind || activeEntry == null) {
                    Log.w(TAG, "channel ${entry.kind} lost/timeout — notifying user of Connecting state")
                    activeEntry = null
                    _state.value = ConnectionState.Connecting
                }
            }

            // ── Connecting state ──
            is ConnectionState.Connecting -> {
                if (activeEntry == null || activeEntry?.kind == entry.kind) {
                    _state.value = ConnectionState.Connecting
                }
            }

            // ── Idle ──
            is ConnectionState.Idle -> { /* ignore */ }
        }
    }

    companion object {
        private const val TAG = "HybridDeviceChannel"
    }
}
