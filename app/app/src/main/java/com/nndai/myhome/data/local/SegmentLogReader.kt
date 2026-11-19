package com.nndai.myhome.data.local

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Date grouping is a presentation concern; physical segments may contain many days. */
class SegmentLogReader(private val cache: SegmentLogCache) {
    fun grouped(stream: Int): Map<String, String> {
        val anchors = mutableMapOf<Pair<Long, Long>, LogRecordCodec.Record>()
        cache.forEachSegment(stream) { segment ->
            LogRecordCodec.decode(segment.data, stream, segment.generation, segment.id, false).records
                .filter { it.type == 2 && it.epoch > 0 }.forEach { record ->
                    anchors.putIfAbsent(segment.generation to record.boot, record)
                }
        }
        val dates = mutableMapOf<String, StringBuilder>()
        val dateFormat = SimpleDateFormat("dd-MM-yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        cache.forEachSegment(stream) { segment ->
            LogRecordCodec.decode(segment.data, stream, segment.generation, segment.id, false).records
                .filter { it.type == 1 }.forEach { record ->
                    val anchor = anchors[segment.generation to record.boot]
                    // Firmware extends millis() wraps before storing uptime in seconds.
                    val epoch = if (record.epoch > 0) record.epoch else anchor?.let {
                        it.epoch + (record.uptime - it.uptime)
                    } ?: 0
                    val date = if (epoch > 0) dateFormat.format(Date(epoch * 1000)) else "Chưa xác định"
                    val time = if (epoch > 0) timeFormat.format(Date(epoch * 1000)) else
                        "?boot=${record.boot}, uptime=${record.uptime}s?"
                    val line = when (stream) {
                        0 -> record.text.substringBeforeLast('|')
                        1 -> "$time|${record.text}"
                        else -> "$time ${record.text}"
                    }
                    dates.getOrPut(date) { StringBuilder() }.append(line).append('\n')
                }
        }
        return dates.mapValues { it.value.toString() }
    }
}
