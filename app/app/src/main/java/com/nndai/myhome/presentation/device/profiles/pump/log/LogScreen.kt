package com.nndai.myhome.presentation.device.profiles.pump.log

import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nndai.myhome.R
import com.nndai.myhome.core.theme.CyanBlue
import com.nndai.myhome.core.theme.GreenOk
import com.nndai.myhome.core.theme.RedError
import com.nndai.myhome.data.repository.LogRepository
import com.nndai.myhome.presentation.device.components.ConfirmDialog
import java.util.Calendar
import java.util.Locale

@Composable
fun LogScreen(
    viewModel: LogViewModel = viewModel(),
    bottomPadding: Dp = 0.dp
) {
    val context = LocalContext.current
    val logTabMode by viewModel.logTabMode.collectAsStateWithLifecycle()
    val isEnabled by viewModel.isLogEnabled.collectAsStateWithLifecycle()
    val selectedSysDate by viewModel.selectedSysDate.collectAsStateWithLifecycle()
    val availableSysDates by viewModel.availableSysDates.collectAsStateWithLifecycle()
    val dailySysLogs by viewModel.dailySysLogs.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val logs = viewModel.logs

    LaunchedEffect(Unit) {
        viewModel.userMessages.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top Mode Switcher (Live vs File Log)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier.padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Mode 0: Live Logs
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .clickable { viewModel.setLogTabMode(0) },
                    shape = MaterialTheme.shapes.extraSmall,
                    color = if (logTabMode == 0) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    contentColor = if (logTabMode == 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.log_mode_live),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (logTabMode == 0) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }

                // Mode 1: File Log (/logs/sys/)
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .clickable { viewModel.setLogTabMode(1) },
                    shape = MaterialTheme.shapes.extraSmall,
                    color = if (logTabMode == 1) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    contentColor = if (logTabMode == 1) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.log_mode_file),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (logTabMode == 1) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        if (logTabMode == 0) {
            // Mode 0: Live MQTT Logs
            LiveLogsContent(
                isEnabled = isEnabled,
                logs = logs,
                bottomPadding = bottomPadding,
                onEnableChanged = { viewModel.setLogEnabled(it) },
                onClearLogs = { viewModel.clearLogs() },
                onSendRawJson = { viewModel.sendRawJson(it) }
            )
        } else {
            // Mode 1: File Log (/logs/sys/)
            FileLogsContent(
                selectedDate = selectedSysDate,
                availableDates = availableSysDates,
                dailySysLogs = dailySysLogs,
                isSyncing = isSyncing,
                onSelectDate = { viewModel.selectSysDate(it) },
                onSyncClick = { viewModel.syncSysLogs(force = true) },
                onDeleteClick = { dateStr -> viewModel.deleteSysLogFile(dateStr) }
            )
        }
    }
}

@Composable
private fun LiveLogsContent(
    isEnabled: Boolean,
    logs: List<String>,
    bottomPadding: Dp,
    onEnableChanged: (Boolean) -> Unit,
    onClearLogs: () -> Unit,
    onSendRawJson: (String) -> Unit
) {
    val listState = rememberLazyListState()

    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems == 0) {
                true
            } else {
                val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisibleIndex >= totalItems - 2
            }
        }
    }

    LaunchedEffect(logs.size) {
        if (isAtBottom && logs.isNotEmpty()) {
            listState.scrollToItem(logs.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.log_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (logs.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "(${logs.size})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (logs.isNotEmpty()) {
                    IconButton(onClick = onClearLogs) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.log_clear_logs),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = if (isEnabled) stringResource(R.string.log_listening) else stringResource(R.string.log_off),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = isEnabled,
                    onCheckedChange = onEnableChanged,
                    modifier = Modifier.scale(0.8f)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val horizontalScrollState = rememberScrollState()

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(horizontalScrollState)
                    .padding(8.dp)
            ) {
                items(logs) { msg ->
                    LogLine(msg)
                }
            }
        }

        // Raw JSON Command Input Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            var rawInput by remember { mutableStateOf("") }

            OutlinedTextField(
                value = rawInput,
                onValueChange = { rawInput = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = stringResource(R.string.log_raw_json_hint),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                ),
                shape = MaterialTheme.shapes.small,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (rawInput.isNotBlank()) {
                            onSendRawJson(rawInput)
                            rawInput = ""
                        }
                    }
                )
            )

            Button(
                onClick = {
                    if (rawInput.isNotBlank()) {
                        onSendRawJson(rawInput)
                        rawInput = ""
                    }
                },
                modifier = Modifier.height(50.dp),
                shape = MaterialTheme.shapes.small
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.log_send),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.log_send), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Dynamic IME Spacer
        val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
        val extraImePadding = (imeBottom - bottomPadding).coerceAtLeast(0.dp)
        if (extraImePadding > 0.dp) {
            Spacer(modifier = Modifier.height(extraImePadding))
        }
    }
}

@Composable
private fun FileLogsContent(
    selectedDate: String,
    availableDates: List<String>,
    dailySysLogs: Map<String, String>,
    isSyncing: Boolean,
    onSelectDate: (String) -> Unit,
    onSyncClick: () -> Unit,
    onDeleteClick: (String) -> Unit
) {
    var showDatePickerDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "sysSyncSpin")
    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sysSpinAngle"
    )

    val sysContent = dailySysLogs[selectedDate] ?: ""
    val logLines = remember(sysContent) {
        if (sysContent.isBlank()) emptyList()
        else sysContent.lines().filter { it.isNotBlank() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Select Date Button
            Surface(
                onClick = { showDatePickerDialog = true },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.DateRange,
                        contentDescription = null,
                        tint = CyanBlue,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (selectedDate.isNotBlank()) "$selectedDate.log" else "Select file",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (logLines.isNotEmpty()) {
                    Text(
                        text = "(${logLines.size} lines)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (selectedDate.isNotBlank() && (dailySysLogs.containsKey(selectedDate) || availableDates.contains(selectedDate))) {
                    IconButton(onClick = { showDeleteConfirmDialog = true }) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.log_delete_file_title),
                            tint = RedError
                        )
                    }
                }
                IconButton(onClick = onSyncClick) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.history_sync),
                        tint = CyanBlue,
                        modifier = Modifier.graphicsLayer {
                            rotationZ = if (isSyncing) spinAngle else 0f
                        }
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(0.3f), MaterialTheme.shapes.medium)
        ) {
            if (logLines.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.log_no_file_data),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onSyncClick,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.log_sync_from_device))
                    }
                }
            } else {
                val horizontalScrollState = rememberScrollState()

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalScroll(horizontalScrollState)
                        .padding(8.dp)
                ) {
                    items(logLines) { line ->
                        LogLine(line)
                    }
                }
            }
        }
    }

    if (showDatePickerDialog) {
        CustomSysLogCalendarDialog(
            title = stringResource(R.string.history_select_date),
            selectedDateStr = selectedDate,
            availableDates = availableDates,
            onDateSelected = { dateStr ->
                onSelectDate(dateStr)
                showDatePickerDialog = false
            },
            onDismiss = { showDatePickerDialog = false }
        )
    }

    if (showDeleteConfirmDialog) {
        ConfirmDialog(
            title = stringResource(R.string.log_delete_file_title),
            message = stringResource(R.string.log_delete_file_confirm, "$selectedDate.log"),
            confirmText = stringResource(R.string.log_delete_action),
            isDangerous = true,
            onConfirm = {
                showDeleteConfirmDialog = false
                onDeleteClick(selectedDate)
            },
            onDismiss = { showDeleteConfirmDialog = false }
        )
    }
}

@Composable
private fun CustomSysLogCalendarDialog(
    title: String,
    selectedDateStr: String,
    availableDates: List<String>,
    onDateSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val todayStr = remember { LogRepository.getTodayDateStr() }

    val initialDateParts = remember(selectedDateStr) {
        val parts = selectedDateStr.split("-")
        if (parts.size >= 3) {
            Triple(
                parts[0].toIntOrNull() ?: 1,
                parts[1].toIntOrNull() ?: (Calendar.getInstance().get(Calendar.MONTH) + 1),
                parts[2].toIntOrNull() ?: Calendar.getInstance().get(Calendar.YEAR)
            )
        } else {
            val cal = Calendar.getInstance()
            Triple(cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR))
        }
    }

    var displayYear by remember { mutableStateOf(initialDateParts.third) }
    var displayMonth by remember { mutableStateOf(initialDateParts.second) }

    val cal = remember(displayYear, displayMonth) {
        Calendar.getInstance().apply {
            set(Calendar.YEAR, displayYear)
            set(Calendar.MONTH, displayMonth - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }
    }

    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
    val offset = (firstDayOfWeek + 5) % 7

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Surface(
                    modifier = Modifier.clickable {
                        onDateSelected(todayStr)
                        onDismiss()
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = CyanBlue.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, CyanBlue.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = stringResource(R.string.history_today),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyanBlue,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (displayMonth == 1) {
                                displayMonth = 12
                                displayYear -= 1
                            } else {
                                displayMonth -= 1
                            }
                        }
                    ) {
                        Text("<", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = CyanBlue)
                    }

                    Text(
                        text = String.format(Locale.US, stringResource(R.string.history_month_header), displayMonth, displayYear),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    IconButton(
                        onClick = {
                            if (displayMonth == 12) {
                                displayMonth = 1
                                displayYear += 1
                            } else {
                                displayMonth += 1
                            }
                        }
                    ) {
                        Text(">", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = CyanBlue)
                    }
                }

                val weekDays = LocalContext.current.resources.getStringArray(R.array.history_week_days)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    weekDays.forEachIndexed { idx, dayName ->
                        Text(
                            text = dayName,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (idx == 6) RedError else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                val totalCells = offset + daysInMonth
                val totalRows = (totalCells + 6) / 7

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (r in 0 until totalRows) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            for (c in 0..6) {
                                val cellIdx = r * 7 + c
                                val dayNum = cellIdx - offset + 1

                                if (cellIdx < offset || dayNum > daysInMonth) {
                                    Spacer(modifier = Modifier.weight(1f).height(36.dp))
                                } else {
                                    val dateStr = String.format(Locale.US, "%02d-%02d-%04d", dayNum, displayMonth, displayYear)
                                    val isSelected = (dateStr == selectedDateStr)
                                    val isToday = (dateStr == todayStr)
                                    val hasData = availableDates.contains(dateStr)

                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(38.dp)
                                            .clip(CircleShape)
                                            .clickable {
                                                onDateSelected(dateStr)
                                                onDismiss()
                                            },
                                        shape = CircleShape,
                                        color = when {
                                            isSelected -> CyanBlue
                                            else -> Color.Transparent
                                        },
                                        border = when {
                                            !isSelected && isToday -> BorderStroke(1.5.dp, CyanBlue)
                                            else -> null
                                        }
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxSize(),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text(
                                                text = "$dayNum",
                                                fontSize = 12.sp,
                                                fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
                                                color = when {
                                                    isSelected -> Color.White
                                                    c == 6 -> RedError
                                                    else -> MaterialTheme.colorScheme.onSurface
                                                }
                                            )
                                            if (hasData && !isSelected) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(4.dp)
                                                        .clip(RoundedCornerShape(2.dp))
                                                        .background(GreenOk)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

private fun extractLogLevel(msg: String): Char? {
    val trimmed = msg.trim()
    if (trimmed.isEmpty()) return null

    val firstChar = trimmed.first()
    if (firstChar in listOf('V', 'D', 'I', 'W', 'E', 'F', 'T')) {
        val nextChar = trimmed.getOrNull(1)
        if (nextChar == null || nextChar in " /:(.[") return firstChar
    }

    val regex = Regex("""(?:^|[\s\[])([VDIWEFT])(?:[\]\s/:(.])""")
    val match = regex.find(trimmed)
    if (match != null) {
        return match.groupValues[1].firstOrNull()
    }

    return null
}

@Composable
private fun LogLine(msg: String) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val level = remember(msg) { extractLogLevel(msg) }
    val color = when (level) {
        'V', 'T' -> if (isDark) Color(0xFFAAAAAA) else Color(0xFF757575)
        'D' -> if (isDark) Color(0xFF4FC3F7) else Color(0xFF0288D1)
        'I' -> if (isDark) Color(0xFF81C784) else Color(0xFF388E3C)
        'W' -> if (isDark) Color(0xFFFFD54F) else Color(0xFFF57C00)
        'E', 'F' -> if (isDark) Color(0xFFE57373) else Color(0xFFD32F2F)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text(
        text = msg,
        color = color,
        fontSize = 8.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        softWrap = false,
        lineHeight = 10.sp
    )
}
