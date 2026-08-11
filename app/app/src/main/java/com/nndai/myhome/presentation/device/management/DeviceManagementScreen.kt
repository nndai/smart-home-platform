package com.nndai.myhome.presentation.device.management

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeviceUnknown
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.ModeFanOff
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.github.jan.supabase.auth.auth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nndai.myhome.core.theme.CyanBlue
import com.nndai.myhome.core.theme.GreenOk
import com.nndai.myhome.core.theme.OrangeWarning
import com.nndai.myhome.core.theme.SecondaryText
import com.nndai.myhome.data.model.Device
import com.nndai.myhome.data.repository.DeviceManagerRepository

@Composable
fun DeviceManagementScreen(
    deviceRepository: DeviceManagerRepository,
    isLoggedIn: Boolean,
    onNavigateToLogin: () -> Unit,
    onAddDeviceClick: () -> Unit,
    onNavigateToDevice: (String, String) -> Unit
) {
    val devices by deviceRepository.devices.collectAsState()
    val lastError by deviceRepository.lastError.collectAsState()

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { if (isLoggedIn) onAddDeviceClick() else onNavigateToLogin() },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = MaterialTheme.shapes.large
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add Device")
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Header
            Text(
                text = "Devices",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "${devices.size} device(s) registered",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Device list
            AnimatedVisibility(
                visible = devices.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(devices) { device ->
                        DeviceListItem(
                            device = device,
                            onClick = {
                                if (isLoggedIn) onNavigateToDevice(device.device_id, device.profile)
                                else onNavigateToLogin()
                            }
                        )
                    }
                }
            }

            // Error state
            if (lastError != null) {
                Spacer(modifier = Modifier.height(48.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeviceUnknown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                        modifier = Modifier.size(56.dp)
                    )
                    Text(
                        text = lastError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
            } else if (devices.isEmpty()) {
                Spacer(modifier = Modifier.height(48.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Devices,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(56.dp)
                    )
                    Text(
                        text = "No devices yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (isLoggedIn) "Tap + to add your first device"
                        else "Sign in to manage devices",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceListItem(
    device: Device,
    onClick: () -> Unit
) {
    val icon = when (device.profile.lowercase()) {
        "pump" -> Icons.Filled.WaterDrop
        "fan" -> Icons.Filled.ModeFanOff
        "lamp", "switch" -> Icons.Filled.Lightbulb
        else -> Icons.Filled.DeviceUnknown
    }
    val iconTint = when (device.profile.lowercase()) {
        "pump" -> CyanBlue
        "fan" -> GreenOk
        "lamp", "switch" -> OrangeWarning
        else -> SecondaryText
    }
    val currentUserId = remember {
        try {
            com.nndai.myhome.data.remote.SupabaseConfig.client.auth.currentSessionOrNull()?.user?.id
        } catch (e: Exception) { null }
    }
    val isTransferred = device.isTransferred(currentUserId)

    val handshakeMgr = remember { com.nndai.myhome.data.di.PumpRepositoryProvider.provideDeviceHandshakeManager() }
    val healthStateFlow = remember(device.device_id) { handshakeMgr.registerDevice(device.device_id) }
    val healthState by healthStateFlow.collectAsState()

    val statusText: String
    val statusColor: androidx.compose.ui.graphics.Color
    val statusBg: androidx.compose.ui.graphics.Color

    if (isTransferred) {
        statusText = "Đã đổi chủ"
        statusColor = androidx.compose.ui.graphics.Color(0xFF9C27B0)
        statusBg = androidx.compose.ui.graphics.Color(0xFF9C27B0).copy(alpha = 0.12f)
    } else {
        when (healthState) {
            is com.nndai.myhome.data.remote.DeviceHealthStatus.Online -> {
                statusText = "Online"
                statusColor = GreenOk
                statusBg = GreenOk.copy(alpha = 0.12f)
            }
            is com.nndai.myhome.data.remote.DeviceHealthStatus.Handshaking -> {
                statusText = "Connecting..."
                statusColor = OrangeWarning
                statusBg = OrangeWarning.copy(alpha = 0.12f)
            }
            is com.nndai.myhome.data.remote.DeviceHealthStatus.Offline -> {
                statusText = "Offline"
                statusColor = SecondaryText
                statusBg = SecondaryText.copy(alpha = 0.1f)
            }
            else -> {
                statusText = "Unknown"
                statusColor = SecondaryText
                statusBg = SecondaryText.copy(alpha = 0.1f)
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Icon container
            Surface(
                shape = MaterialTheme.shapes.small,
                color = iconTint.copy(alpha = 0.12f),
                modifier = Modifier.size(44.dp)
            ) {
                androidx.compose.foundation.layout.Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Device info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = device.device_id,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Real-Time Status badge
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = statusBg,
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    fontWeight = FontWeight.Medium,
                    color = statusColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}
