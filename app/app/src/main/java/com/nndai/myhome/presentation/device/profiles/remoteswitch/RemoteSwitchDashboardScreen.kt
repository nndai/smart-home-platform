package com.nndai.myhome.presentation.device.profiles.remoteswitch

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ModeFanOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SmartButton
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nndai.myhome.core.theme.CyanBlue
import com.nndai.myhome.core.theme.GreenOk
import com.nndai.myhome.core.theme.OrangeWarning
import com.nndai.myhome.core.theme.RedError
import com.nndai.myhome.data.model.ConnectionState
import com.nndai.myhome.data.model.Device

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteSwitchDashboardScreen(
    snackbarHostState: SnackbarHostState,
    viewModel: RemoteSwitchViewModel = viewModel()
) {
    val status by viewModel.deviceStatus.collectAsStateWithLifecycle()
    val config by viewModel.deviceConfig.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val ownedDevices by viewModel.ownedCandidateDevices.collectAsStateWithLifecycle()

    val isToggling by viewModel.isToggling.collectAsStateWithLifecycle()
    val isSettingTarget by viewModel.isSettingTarget.collectAsStateWithLifecycle()
    val isClearingTarget by viewModel.isClearingTarget.collectAsStateWithLifecycle()

    var showTargetSelectorSheet by remember { mutableStateOf(false) }
    var showConfirmClearDialog by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val targetId = status?.targetId?.ifBlank { config?.targetId } ?: ""
    val targetType = status?.targetType?.ifBlank { config?.targetType } ?: ""
    val isTargetConfigured = targetId.isNotBlank()
    val isRelayOn = status?.relay == true
    val hasTargetError = status?.targetError == true

    val targetDevice = remember(targetId, ownedDevices) {
        ownedDevices.find { it.device_id == targetId }
    }
    val targetDisplayName = targetDevice?.name ?: if (isTargetConfigured) targetId else "Chưa thiết lập"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Header Bar: Connection Status & Signal
        ConnectionHeaderCard(
            connectionState = connectionState,
            rssi = status?.rssi ?: 0,
            onRefresh = { viewModel.refreshStatus() },
            onReconnect = { viewModel.reconnect() }
        )

        // 2. Hero Card: Target Device Control
        if (isTargetConfigured) {
            ConfiguredTargetCard(
                targetName = targetDisplayName,
                targetId = targetId,
                targetType = targetType,
                isRelayOn = isRelayOn,
                isToggling = isToggling,
                hasError = hasTargetError,
                onToggleRelay = { viewModel.toggleRelay() },
                onChangeTarget = { showTargetSelectorSheet = true },
                onClearTarget = { showConfirmClearDialog = true }
            )
        } else {
            UnconfiguredTargetCard(
                onSelectTarget = { showTargetSelectorSheet = true }
            )
        }

        // 3. Hardware Reference & LED Guide
        HardwareGuideCard()

        Spacer(modifier = Modifier.height(16.dp))
    }

    // Target Selection Modal BottomSheet
    if (showTargetSelectorSheet) {
        ModalBottomSheet(
            onDismissRequest = { showTargetSelectorSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            TargetSelectionSheetContent(
                devices = ownedDevices,
                currentTargetId = targetId,
                isSettingTarget = isSettingTarget,
                hasKeyChecker = { viewModel.hasControlKey(it) },
                onSelectDevice = { device ->
                    viewModel.setTarget(device)
                    showTargetSelectorSheet = false
                },
                onDismiss = { showTargetSelectorSheet = false }
            )
        }
    }

    // Confirm Clear Target Dialog
    if (showConfirmClearDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmClearDialog = false },
            icon = {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = null,
                    tint = RedError,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "Xóa liên kết mục tiêu?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Remote Switch sẽ ngắt kết nối với '$targetDisplayName'. Nút bấm vật lý sẽ không điều khiển thiết bị này nữa cho đến khi bạn liên kết lại.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearTarget()
                        showConfirmClearDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    if (isClearingTarget) {
                        CircularProgressIndicator(
                            color = RedError,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Xóa mục tiêu", color = RedError, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmClearDialog = false }) {
                    Text("Hủy")
                }
            }
        )
    }
}

// ── COMPONENT 1: Header Card ──
@Composable
private fun ConnectionHeaderCard(
    connectionState: ConnectionState,
    rssi: Int,
    onRefresh: () -> Unit,
    onReconnect: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val isConnected = connectionState is ConnectionState.Connected
                val dotColor = if (isConnected) GreenOk else OrangeWarning

                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )

                Column {
                    Text(
                        text = if (isConnected) "Remote Switch Online" else "Đang kết nối...",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isConnected) "MQTT TLS 8883 (E2E Encrypted)" else "Awaiting connection",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (rssi != 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Wifi,
                            contentDescription = null,
                            tint = CyanBlue,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "$rssi dBm",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(
                    onClick = {
                        if (connectionState is ConnectionState.Connected) onRefresh()
                        else onReconnect()
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (connectionState is ConnectionState.Connected) Icons.Filled.Refresh else Icons.Filled.Sync,
                        contentDescription = "Refresh",
                        tint = CyanBlue,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

// ── COMPONENT 2: Hero Configured Target Card ──
@Composable
private fun ConfiguredTargetCard(
    targetName: String,
    targetId: String,
    targetType: String,
    isRelayOn: Boolean,
    isToggling: Boolean,
    hasError: Boolean,
    onToggleRelay: () -> Unit,
    onChangeTarget: () -> Unit,
    onClearTarget: () -> Unit
) {
    val targetIcon = getProfileIcon(targetType)
    val powerColor by animateColorAsState(
        targetValue = if (isRelayOn) GreenOk else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        animationSpec = tween(300),
        label = "powerColor"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (isRelayOn) GreenOk.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Target header info
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, CyanBlue.copy(alpha = 0.3f))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = targetIcon,
                                contentDescription = null,
                                tint = CyanBlue,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    Column {
                        Text(
                            text = targetName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = targetId,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = CyanBlue.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = targetType.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CyanBlue,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }

                // E2E Security Badge
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = GreenOk.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, GreenOk.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = GreenOk,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "E2E AES-GCM",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = GreenOk
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

            // Main Relay Switch Display
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (isRelayOn) "THIẾT BỊ ĐANG BẬT" else "THIẾT BỊ ĐANG TẮT",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp,
                    color = if (isRelayOn) GreenOk else MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Large Glowing Toggle Button
                val scale by animateFloatAsState(
                    targetValue = if (isToggling) 0.92f else 1f,
                    animationSpec = tween(150),
                    label = "scale"
                )

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.scale(scale)
                ) {
                    // Outer Glow Ring
                    if (isRelayOn) {
                        Box(
                            modifier = Modifier
                                .size(130.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            GreenOk.copy(alpha = 0.35f),
                                            Color.Transparent
                                        )
                                    )
                                )
                        )
                    }

                    // Main Circular Button
                    Surface(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(CircleShape)
                            .clickable(enabled = !isToggling, onClick = onToggleRelay),
                        shape = CircleShape,
                        color = if (isRelayOn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(3.dp, powerColor),
                        shadowElevation = if (isRelayOn) 8.dp else 2.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isToggling) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(36.dp),
                                    color = powerColor,
                                    strokeWidth = 3.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.PowerSettingsNew,
                                    contentDescription = "Toggle Relay",
                                    tint = powerColor,
                                    modifier = Modifier.size(46.dp)
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "Chạm để bật/tắt thiết bị mục tiêu",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Error Warning Banner if target device has fault
            AnimatedVisibility(
                visible = hasError,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = RedError.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, RedError.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = RedError,
                            modifier = Modifier.size(22.dp)
                        )
                        Column {
                            Text(
                                text = "Cảnh báo sự cố từ thiết bị mục tiêu",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = RedError
                            )
                            Text(
                                text = "Thiết bị mục tiêu báo lỗi bảo vệ (Cạn nước / Quá tải). Đã tự động ngắt relay để bảo vệ an toàn.",
                                style = MaterialTheme.typography.labelSmall,
                                color = RedError.copy(alpha = 0.9f)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

            // Action Buttons (Change Target & Unlink Target)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onChangeTarget,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, CyanBlue.copy(alpha = 0.5f))
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SwapHoriz,
                        contentDescription = null,
                        tint = CyanBlue,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Đổi mục tiêu",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = CyanBlue
                    )
                }

                Button(
                    onClick = onClearTarget,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = RedError,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Xóa mục tiêu",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = RedError
                    )
                }
            }
        }
    }
}

// ── COMPONENT 3: Unconfigured Empty State Card ──
@Composable
private fun UnconfiguredTargetCard(
    onSelectTarget: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.LinkOff,
                        contentDescription = null,
                        tint = OrangeWarning,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Chưa liên kết thiết bị mục tiêu",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Remote Switch cần được liên kết với một máy bơm hoặc công tắc khác để thực hiện điều khiển bật/tắt an toàn từ xa.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            Button(
                onClick = onSelectTarget,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.AddLink,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Chọn thiết bị mục tiêu ngay",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ── COMPONENT 4: Hardware Reference Guide ──
@Composable
private fun HardwareGuideCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Sensors,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Hướng dẫn nút bấm & đèn LED vật lý",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GuideRow(
                    badgeText = "1 Click",
                    badgeColor = CyanBlue,
                    title = "Bật / Tắt mục tiêu",
                    subtitle = "Gửi lệnh toggle đến máy bơm/công tắc mục tiêu"
                )
                GuideRow(
                    badgeText = "2 Click",
                    badgeColor = GreenOk,
                    title = "Lấy trạng thái",
                    subtitle = "Cập nhật tức thì trạng thái mục tiêu qua MQTT"
                )
                GuideRow(
                    badgeText = "Giữ 5s",
                    badgeColor = OrangeWarning,
                    title = "Menu cấu hình",
                    subtitle = "Bước 1: Reset WiFi • Bước 2: Debug Mode • Bước 3: Factory Reset"
                )
            }
        }
    }
}

@Composable
private fun GuideRow(
    badgeText: String,
    badgeColor: Color,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = badgeColor.copy(alpha = 0.15f),
            border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.3f)),
            modifier = Modifier.width(62.dp)
        ) {
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = badgeColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 3.dp)
            )
        }
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── COMPONENT 5: Target Device Selection Bottom Sheet ──
@Composable
private fun TargetSelectionSheetContent(
    devices: List<Device>,
    currentTargetId: String,
    isSettingTarget: Boolean,
    hasKeyChecker: (String) -> Boolean,
    onSelectDevice: (Device) -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.AddLink,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = "Chọn thiết bị mục tiêu để điều khiển",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Text(
            text = "Danh sách chỉ bao gồm các thiết bị thuộc quyền sở hữu của bạn. Khóa điều khiển sẽ được mã hóa đầu cuối E2E (AES-256-GCM) trước khi gửi qua MQTT.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (devices.isEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = "Không tìm thấy thiết bị nào khả dụng",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Hãy đảm bảo bạn đã liên kết (pair) máy bơm hoặc công tắc vào tài khoản.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                devices.forEach { device ->
                    val isSelected = device.device_id == currentTargetId
                    val hasKey = hasKeyChecker(device.device_id)

                    TargetDeviceItemRow(
                        device = device,
                        isSelected = isSelected,
                        hasKey = hasKey,
                        isProcessing = isSettingTarget,
                        onSelect = { onSelectDevice(device) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun TargetDeviceItemRow(
    device: Device,
    isSelected: Boolean,
    hasKey: Boolean,
    isProcessing: Boolean,
    onSelect: () -> Unit
) {
    val profile = device.profile.lowercase()
    val icon = getProfileIcon(profile)
    val profileColor = when (profile) {
        "pump" -> CyanBlue
        "fan" -> GreenOk
        else -> OrangeWarning
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !isProcessing, onClick = onSelect),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    modifier = Modifier.size(38.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = profileColor.copy(alpha = 0.15f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = profileColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Column {
                    Text(
                        text = device.name.ifBlank { device.device_id },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${device.device_id} • ${device.profile.uppercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (hasKey) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = GreenOk.copy(alpha = 0.12f),
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(Icons.Filled.Key, contentDescription = null, tint = GreenOk, modifier = Modifier.size(10.dp))
                            Text("Khóa OK", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = GreenOk)
                        }
                    }
                }
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

private fun getProfileIcon(profile: String): ImageVector {
    return when (profile.lowercase()) {
        "pump" -> Icons.Filled.WaterDrop
        "fan" -> Icons.Filled.ModeFanOff
        "lamp", "switch" -> Icons.Filled.Lightbulb
        else -> Icons.Filled.DeviceHub
    }
}
