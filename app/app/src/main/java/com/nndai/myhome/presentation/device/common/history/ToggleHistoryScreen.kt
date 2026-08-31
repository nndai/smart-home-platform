package com.nndai.myhome.presentation.device.common.history

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nndai.myhome.R
import com.nndai.myhome.core.theme.CyanBlue
import com.nndai.myhome.data.repository.LogRepository

/**
 * Toggle History Screen for RemoteSwitch / Switch devices.
 * Displays 3 summary cards (Total, ON, OFF), followed by the shared ToggleEventsCard and CustomVisualCalendarDialog.
 */
@Composable
fun ToggleHistoryScreen(
    viewModel: ToggleHistoryViewModel = viewModel()
) {
    val selectedToggleDate by viewModel.selectedToggleDate.collectAsStateWithLifecycle()
    val availableDates by viewModel.availableDates.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val toggleEvents by viewModel.toggleEvents.collectAsStateWithLifecycle()

    var showToggleDatePickerDialog by remember { mutableStateOf(false) }
    val todayStr = remember { LogRepository.getTodayDateStr() }

    LaunchedEffect(Unit) {
        viewModel.syncData()
    }

    val infiniteTransition = rememberInfiniteTransition(label = "syncSpin")
    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spinAngle"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 0.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Top Bar Header (Title + Sync Button)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.toggle_history_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            IconButton(onClick = { viewModel.syncData(force = true) }) {
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

        // Shared Toggle Events Card (includes embedded Summary Badges + List + Date Selector)
        ToggleEventsCard(
            selectedDate = selectedToggleDate,
            availableDates = availableDates,
            todayStr = todayStr,
            events = toggleEvents,
            onSelectDateClick = { showToggleDatePickerDialog = true }
        )

        Spacer(modifier = Modifier.height(80.dp))
    }

    // Shared Day/Calendar Picker Dialog
    if (showToggleDatePickerDialog) {
        CustomVisualCalendarDialog(
            title = stringResource(R.string.history_select_date),
            selectedDateStr = selectedToggleDate,
            availableDates = availableDates,
            onDateSelected = { dateStr ->
                viewModel.selectToggleDate(dateStr)
                showToggleDatePickerDialog = false
            },
            onDismiss = { showToggleDatePickerDialog = false }
        )
    }
}
