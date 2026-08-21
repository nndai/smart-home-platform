package com.nndai.myhome.presentation.device.common.deviceinfo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nndai.myhome.R
import com.nndai.myhome.core.utils.formatBytes
import com.nndai.myhome.core.utils.formatUptime
import java.util.Locale

private fun getSectionIcon(sectionName: String): ImageVector {
    return when (sectionName.lowercase()) {
        "system" -> Icons.Filled.DeveloperBoard
        "memory" -> Icons.Filled.SdStorage
        "tasks" -> Icons.Filled.TaskAlt
        "wifi" -> Icons.Filled.Wifi
        "storage" -> Icons.Filled.Storage
        "pump" -> Icons.Filled.WaterDrop
        else -> Icons.Filled.Info
    }
}

@Composable
fun DeviceInfoScreen(
    viewModel: DeviceInfoViewModel = viewModel()
) {
    val info by viewModel.deviceInfo.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        val data = info?.data
        if (data.isNullOrEmpty()) {
            Text(
                text = stringResource(R.string.system_no_info),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val sortedSections = remember(data) {
                data.entries.sortedWith(
                    compareBy { (sectionName, _) ->
                        if (sectionName.equals("tasks", ignoreCase = true) || sectionName.lowercase().contains("task")) 1 else 0
                    }
                )
            }

            sortedSections.forEach { (sectionName, sectionData) ->
                SectionCard(
                    title = sectionName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                    rawName = sectionName,
                    data = sectionData
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { viewModel.refreshInfo() },
            enabled = !isRefreshing,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.padding(end = 6.dp)
            )
            Text(if (isRefreshing) stringResource(R.string.system_refreshing) else stringResource(R.string.system_refresh))
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}

@Composable
private fun SectionCard(title: String, rawName: String, data: Any) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = getSectionIcon(rawName),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            RenderSectionContent(data)
        }
    }
}

@Composable
private fun RenderSectionContent(data: Any) {
    when (data) {
        is Map<*, *> -> {
            val entries = data.entries.toList()
            entries.forEachIndexed { index, entry ->
                val key = entry.key.toString()
                val valObj = entry.value
                when (valObj) {
                    is List<*> -> {
                        Text(
                            text = key.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                        )
                        RenderListItems(valObj)
                    }
                    is Map<*, *> -> {
                        Text(
                            text = key.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                        )
                        RenderSectionContent(valObj)
                    }
                    else -> {
                        InfoRow(
                            label = key.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                            value = formatDynamicValue(key, valObj),
                            showDivider = index < entries.size - 1
                        )
                    }
                }
            }
        }
        is List<*> -> {
            RenderListItems(data)
        }
        else -> {
            InfoRow(
                label = "Value",
                value = formatDynamicValue("val", data),
                showDivider = false
            )
        }
    }
}

@Composable
private fun RenderListItems(items: List<*>) {
    val sortedItems = remember(items) {
        if (items.firstOrNull() is Map<*, *>) {
            items.sortedBy { item ->
                val map = item as? Map<*, *>
                val name = map?.get("name")?.toString()
                    ?: map?.get("task")?.toString()
                    ?: ""
                name.lowercase(Locale.US)
            }
        } else {
            items
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        sortedItems.forEachIndexed { index, item ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                shape = MaterialTheme.shapes.small
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    if (item is Map<*, *>) {
                        val taskName = item["name"]?.toString() ?: item["task"]?.toString() ?: "Task #${index + 1}"
                        Text(
                            text = taskName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        item.entries.forEach { (k, v) ->
                            val keyStr = k.toString()
                            if (keyStr.lowercase() != "name" && keyStr.lowercase() != "task") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = keyStr.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = formatDynamicValue(keyStr, v),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "#${index + 1}: ${formatDynamicValue("item", item)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    showDivider: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
    if (showDivider) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
        )
    }
}

private fun formatDynamicValue(key: String, value: Any?): String {
    if (value == null) return "N/A"
    val lowerKey = key.lowercase()

    if (lowerKey == "uptime") {
        return (value as? Number)?.toLong()?.formatUptime() ?: value.toString()
    }
    if (lowerKey in listOf("freeheap", "mineverfreeheap", "flashsize", "fstotal", "fsused", "stack", "watermark", "freestack")) {
        if (lowerKey.contains("heap") || lowerKey.contains("flash") || lowerKey.contains("fs")) {
            return (value as? Number)?.toLong()?.formatBytes() ?: value.toString()
        }
    }
    if (lowerKey == "mac") {
        return value.toString().uppercase()
    }
    if (lowerKey == "cpufreq") {
        return "$value MHz"
    }

    if (lowerKey.contains("temp")) {
        val numStr = value.toString()
        return if (numStr.endsWith("°C")) numStr else "$numStr °C"
    }

    if (lowerKey.contains("rssi")) {
        val numStr = value.toString()
        return if (numStr.endsWith("dBm")) numStr else "$numStr dBm"
    }

    if (value is Boolean) {
        return if (value) "Yes" else "No"
    }
    if (value is Float || value is Double) {
        return String.format("%.2f", (value as Number).toDouble())
    }

    return value.toString()
}
