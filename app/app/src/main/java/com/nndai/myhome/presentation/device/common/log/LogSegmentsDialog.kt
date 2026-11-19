package com.nndai.myhome.presentation.device.common.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nndai.myhome.data.model.LogSegment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun LogSegmentsDialog(segments: List<LogSegment>, onDismiss: () -> Unit, onDelete: (LogSegment) -> Unit) {
    var selected by remember { mutableStateOf<LogSegment?>(null) }
    fun range(segment: LogSegment): String {
        if (segment.minEpoch == 0L) return "Chưa xác định thời gian"
        val format = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return "${format.format(Date(segment.minEpoch * 1000))} – ${format.format(Date(segment.maxEpoch * 1000))}"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dữ liệu trên thiết bị") },
        text = {
            Column {
                Text("Mỗi segment có thể chứa nhiều ngày. Bản đã tải vẫn được giữ trong bộ nhớ đệm.")
                if (segments.isEmpty()) Text("Chưa có segment hoặc thiết bị chưa hỗ trợ.")
                LazyColumn {
                    items(segments, key = { "${it.stream}:${it.generation}:${it.id}" }) { segment ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text("#${segment.id} · ${segment.size} byte", style = MaterialTheme.typography.labelLarge)
                                Text(range(segment), style = MaterialTheme.typography.bodySmall)
                                if (!segment.sealed) Text("Đang ghi", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { selected = segment }) { Text("Xóa") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Đóng") } }
    )
    selected?.let { segment ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("Xóa segment #${segment.id}?") },
            text = { Text("Xóa dữ liệu trên thiết bị trong khoảng ${range(segment)}. Khoảng thời gian chưa xác định cũng có thể bị ảnh hưởng.") },
            confirmButton = { TextButton(onClick = { onDelete(segment); selected = null }) { Text("Xóa") } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Hủy") } }
        )
    }
}
