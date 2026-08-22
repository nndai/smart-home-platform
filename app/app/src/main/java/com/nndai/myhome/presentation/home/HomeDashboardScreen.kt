package com.nndai.myhome.presentation.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeviceUnknown
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.ModeFanOff
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nndai.myhome.core.theme.CyanBlue
import com.nndai.myhome.core.theme.GreenOk
import com.nndai.myhome.core.theme.OrangeWarning
import com.nndai.myhome.core.theme.SecondaryText
import com.nndai.myhome.presentation.device.components.DeviceHealthIndicator
import com.nndai.myhome.data.model.Device
import com.nndai.myhome.data.repository.DeviceManagerRepository

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeDashboardScreen(
    deviceRepository: DeviceManagerRepository,
    isLoggedIn: Boolean,
    onNavigateToLogin: () -> Unit,
    onNavigateToDevice: (String, String) -> Unit
) {
    val devices by deviceRepository.devices.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Text(
            text = "My Home",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        // Guest banner
        if (!isLoggedIn) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onNavigateToLogin),
                shape = MaterialTheme.shapes.small,
                color = CyanBlue.copy(alpha = 0.12f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Login,
                        contentDescription = null,
                        tint = CyanBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Sign in to access your devices",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = CyanBlue
                    )
                }
            }
        }

        // Devices grid
        AnimatedVisibility(
            visible = devices.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 2
            ) {
                devices.forEach { device ->
                    DeviceCard(
                        device = device,
                        onClick = {
                            if (isLoggedIn) onNavigateToDevice(device.device_id, device.profile)
                            else onNavigateToLogin()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Empty state
        if (devices.isEmpty()) {
            Spacer(modifier = Modifier.height(48.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.CloudOff,
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
                    text = "Go to Devices tab to add your first device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun DeviceCard(
    device: Device,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val icon: ImageVector
    val iconTint: androidx.compose.ui.graphics.Color
    when (device.profile.lowercase()) {
        "pump" -> {
            icon = Icons.Filled.WaterDrop
            iconTint = CyanBlue
        }
        "fan" -> {
            icon = Icons.Filled.ModeFanOff
            iconTint = GreenOk
        }
        "remote_switch" -> {
            icon = Icons.Filled.Sensors
            iconTint = androidx.compose.ui.graphics.Color(0xFFAB47BC)
        }
        "lamp", "switch" -> {
            icon = Icons.Filled.Lightbulb
            iconTint = OrangeWarning
        }
        else -> {
            icon = Icons.Filled.DeviceUnknown
            iconTint = SecondaryText
        }
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
        statusBg = androidx.compose.ui.graphics.Color(0xFF9C27B0).copy(alpha = 0.15f)
    } else {
        when (healthState) {
            is com.nndai.myhome.data.remote.DeviceHealthStatus.Online -> {
                statusText = "Online"
                statusColor = GreenOk
                statusBg = GreenOk.copy(alpha = 0.15f)
            }
            is com.nndai.myhome.data.remote.DeviceHealthStatus.Handshaking -> {
                statusText = "Connecting..."
                statusColor = OrangeWarning
                statusBg = OrangeWarning.copy(alpha = 0.15f)
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

    val isOnline = !isTransferred && healthState is com.nndai.myhome.data.remote.DeviceHealthStatus.Online

    Surface(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (isOnline) iconTint.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = iconTint.copy(alpha = 0.12f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                // Dynamic Real-Time Online/Offline status badge
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = statusBg,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        DeviceHealthIndicator(
                            healthState = healthState,
                            isTransferred = isTransferred,
                            dotSize = 6.dp,
                            iconSize = 10.dp
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = statusColor
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = device.profile.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
