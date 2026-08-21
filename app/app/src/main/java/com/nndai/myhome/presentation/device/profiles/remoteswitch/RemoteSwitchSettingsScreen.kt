package com.nndai.myhome.presentation.device.profiles.remoteswitch

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
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ModeFanOff
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nndai.myhome.core.theme.CyanBlue
import com.nndai.myhome.core.theme.GreenOk
import com.nndai.myhome.core.theme.OrangeWarning
import com.nndai.myhome.core.theme.RedError
import com.nndai.myhome.data.model.Device
import com.nndai.myhome.presentation.device.common.settings.CommonSettingsViewModel
import com.nndai.myhome.presentation.device.common.settings.DeviceActionsCard
import com.nndai.myhome.presentation.device.common.settings.NetworkConnectionModeCard
import com.nndai.myhome.presentation.device.common.settings.SysLogSettingsCard
import com.nndai.myhome.presentation.device.common.settings.WifiScanDialog
import com.nndai.myhome.presentation.device.components.ConfirmDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteSwitchSettingsScreen(
    snackbarHostState: SnackbarHostState,
    settingsViewModel: CommonSettingsViewModel = viewModel(),
    remoteSwitchViewModel: RemoteSwitchViewModel = viewModel()
) {
    val config by settingsViewModel.deviceConfig.collectAsStateWithLifecycle()
    val isSaving by settingsViewModel.isSaving.collectAsStateWithLifecycle()
    val isRebooting by settingsViewModel.isRebooting.collectAsStateWithLifecycle()
    val isScanningWifi by settingsViewModel.isScanningWifi.collectAsStateWithLifecycle()
    val wifiNetworks by settingsViewModel.wifiNetworks.collectAsStateWithLifecycle()
    val showWifiScanDialog by settingsViewModel.showWifiScanDialog.collectAsStateWithLifecycle()
    val showRebootPrompt by settingsViewModel.showRebootPrompt.collectAsStateWithLifecycle()

    val targetStatus by remoteSwitchViewModel.deviceStatus.collectAsStateWithLifecycle()
    val ownedDevices by remoteSwitchViewModel.ownedCandidateDevices.collectAsStateWithLifecycle()
    val isSettingTarget by remoteSwitchViewModel.isSettingTarget.collectAsStateWithLifecycle()

    // Form states
    var connMode by remember(config) { mutableIntStateOf(config?.connMode ?: 1) }
    var wifiSSID by remember(config) { mutableStateOf(config?.wifiSSID ?: "") }
    var wifiPass by remember(config) { mutableStateOf(config?.wifiPass ?: "") }
    var isWifiOpen by remember { mutableStateOf(false) }

    var debugSSID by remember(config) { mutableStateOf(config?.debugSSID ?: "") }
    var debugPass by remember(config) { mutableStateOf(config?.debugPass ?: "") }

    var sysLogFileEnabled by remember(config) { mutableStateOf(config?.sysLogFileEnabled ?: false) }
    var sysLogFileLevel by remember(config) { mutableStateOf(config?.sysLogFileLevel?.toString() ?: "0") }

    // Dialogs
    var showTargetPickerSheet by remember { mutableStateOf(false) }
    var showClearTargetDialog by remember { mutableStateOf(false) }
    var showRebootDialog by remember { mutableStateOf(false) }
    var showFactoryResetDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        settingsViewModel.messages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(Unit) {
        remoteSwitchViewModel.messages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Target Device Setting Card (Remote Switch Specific)
        TargetDeviceCard(
            targetId = targetStatus?.targetId ?: config?.targetId ?: "",
            targetType = targetStatus?.targetType ?: config?.targetType ?: "",
            targetPaired = targetStatus?.targetPaired ?: (config?.targetKey?.isNotBlank() == true),
            onPickTargetClick = {
                remoteSwitchViewModel.refreshStatus()
                showTargetPickerSheet = true
            },
            onClearTargetClick = { showClearTargetDialog = true },
            isPairing = isSettingTarget
        )

        // 2. Network Connection Mode Card (Shared STA / AP / DEBUG)
        NetworkConnectionModeCard(
            connMode = connMode,
            onConnModeChange = { connMode = it },
            wifiSSID = wifiSSID,
            onWifiSSIDChange = { wifiSSID = it },
            wifiPass = wifiPass,
            onWifiPassChange = { wifiPass = it },
            isWifiOpen = isWifiOpen,
            onWifiOpenChange = { isWifiOpen = it },
            debugSSID = debugSSID,
            onDebugSSIDChange = { debugSSID = it },
            debugPass = debugPass,
            onDebugPassChange = { debugPass = it },
            isScanningWifi = isScanningWifi,
            onScanWifiClick = { settingsViewModel.scanWifi() },
            onSaveNetworkClick = {
                val updates = mutableMapOf<String, Any>(
                    "connMode" to connMode
                )
                if (connMode == 1) {
                    updates["wifiSSID"] = wifiSSID
                    updates["wifiPass"] = wifiPass
                } else if (connMode == 2) {
                    updates["debugSSID"] = debugSSID
                    updates["debugPass"] = debugPass
                }
                settingsViewModel.saveConfig(updates)
            },
            isSaving = isSaving
        )

        // 3. SysLog Settings Card (Shared)
        SysLogSettingsCard(
            sysLogFileEnabled = sysLogFileEnabled,
            onSysLogFileEnabledChange = { sysLogFileEnabled = it },
            sysLogFileLevel = sysLogFileLevel,
            onSysLogFileLevelChange = { sysLogFileLevel = it },
            onSaveSysLogClick = {
                val updates = mapOf<String, Any>(
                    "sysLogFileEnabled" to sysLogFileEnabled,
                    "sysLogFileLevel" to (sysLogFileLevel.toIntOrNull() ?: 0)
                )
                settingsViewModel.saveConfig(updates)
            },
            isSaving = isSaving
        )

        // 4. Device Actions Card (Shared Reboot / Factory Reset)
        DeviceActionsCard(
            onRebootClick = { showRebootDialog = true },
            onFactoryResetClick = { showFactoryResetDialog = true },
            isRebooting = isRebooting
        )

        Spacer(modifier = Modifier.height(24.dp))
    }

    // WiFi Scan Dialog
    WifiScanDialog(
        visible = showWifiScanDialog,
        isScanning = isScanningWifi,
        networks = wifiNetworks,
        onSelectNetwork = { network ->
            wifiSSID = network.ssid
            isWifiOpen = !network.isEncrypt
            if (isWifiOpen) {
                wifiPass = ""
            }
        },
        onDismiss = { settingsViewModel.dismissWifiScanDialog() }
    )

    // Clear Target Confirmation Dialog
    if (showClearTargetDialog) {
        ConfirmDialog(
            title = "Xóa liên kết mục tiêu",
            message = "Bạn có chắc chắn muốn ngắt liên kết công tắc này khỏi thiết bị mục tiêu không?",
            confirmText = "Xóa liên kết",
            isDangerous = true,
            onConfirm = {
                showClearTargetDialog = false
                remoteSwitchViewModel.clearTarget()
            },
            onDismiss = { showClearTargetDialog = false }
        )
    }

    // Reboot Confirmation Dialog
    if (showRebootDialog || showRebootPrompt) {
        ConfirmDialog(
            title = "Khởi động lại thiết bị",
            message = if (showRebootPrompt) "Cấu hình vừa thay đổi cần khởi động lại để áp dụng. Khởi động lại ngay?" else "Bạn có muốn khởi động lại công tắc không?",
            confirmText = "Khởi động lại",
            onConfirm = {
                showRebootDialog = false
                settingsViewModel.reboot()
            },
            onDismiss = {
                showRebootDialog = false
                settingsViewModel.dismissRebootPrompt()
            }
        )
    }

    // Factory Reset Confirmation Dialog
    if (showFactoryResetDialog) {
        ConfirmDialog(
            title = "Khôi phục cài đặt gốc",
            message = "Thao tác này sẽ xóa toàn bộ thông tin WiFi, cấu hình mục tiêu và mã hóa trên công tắc. Thiết bị sẽ trở về trạng thái xuất xưởng.",
            confirmText = "Khôi phục gốc",
            isDangerous = true,
            onConfirm = {
                showFactoryResetDialog = false
                settingsViewModel.factoryReset()
            },
            onDismiss = { showFactoryResetDialog = false }
        )
    }

    // Target Device Picker Modal Bottom Sheet
    if (showTargetPickerSheet) {
        ModalBottomSheet(
            onDismissRequest = { showTargetPickerSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
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
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "Danh sách chỉ hiển thị các thiết bị thuộc quyền sở hữu của bạn. Khóa điều khiển sẽ được mã hóa đầu cuối (E2E AES-256-GCM) an toàn.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (ownedDevices.isEmpty()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        shape = MaterialTheme.shapes.medium,
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
                                fontWeight = FontWeight.Bold
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
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ownedDevices.forEach { device ->
                            val hasKey = remoteSwitchViewModel.hasControlKey(device.device_id)
                            TargetCandidateItem(
                                device = device,
                                hasKey = hasKey,
                                isPairing = isSettingTarget,
                                onSelect = {
                                    remoteSwitchViewModel.setTarget(device)
                                    showTargetPickerSheet = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun TargetDeviceCard(
    targetId: String,
    targetType: String,
    targetPaired: Boolean,
    onPickTargetClick: () -> Unit,
    onClearTargetClick: () -> Unit,
    isPairing: Boolean,
    modifier: Modifier = Modifier
) {
    val isConfigured = targetId.isNotBlank()

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
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
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.DeviceHub,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Text(
                    text = "Cấu hình thiết bị mục tiêu (Target Device)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (isConfigured) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val icon = if (targetType.equals("pump", ignoreCase = true)) Icons.Filled.WaterDrop else Icons.Filled.Lightbulb
                                val iconTint = if (targetType.equals("pump", ignoreCase = true)) CyanBlue else OrangeWarning
                                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
                                Text(
                                    text = targetId,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = GreenOk.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, GreenOk.copy(alpha = 0.4f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Filled.Lock, contentDescription = null, tint = GreenOk, modifier = Modifier.size(12.dp))
                                    Text("E2E AES-GCM", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = GreenOk)
                                }
                            }
                        }

                        Text(
                            text = "Loại thiết bị: ${if (targetType.equals("pump", ignoreCase = true)) "Máy bơm (Pump)" else "Công tắc (Switch)"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onPickTargetClick,
                        enabled = !isPairing,
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Icon(Icons.Outlined.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Đổi mục tiêu", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = onClearTargetClick,
                        enabled = !isPairing,
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp),
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, tint = RedError, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Xóa mục tiêu", color = RedError, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Filled.LinkOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        Text(
                            text = "Chưa có thiết bị mục tiêu nào được liên kết với công tắc này.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Button(
                    onClick = onPickTargetClick,
                    enabled = !isPairing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = MaterialTheme.shapes.small
                ) {
                    Icon(Icons.Filled.AddLink, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isPairing) "Đang lưu mục tiêu..." else "Chọn thiết bị mục tiêu", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun TargetCandidateItem(
    device: Device,
    hasKey: Boolean,
    isPairing: Boolean,
    onSelect: () -> Unit
) {
    val profile = device.profile.lowercase()
    val icon: ImageVector
    val iconTint: Color
    val profileName: String

    when (profile) {
        "pump" -> {
            icon = Icons.Filled.WaterDrop
            iconTint = CyanBlue
            profileName = "Máy bơm (Pump)"
        }
        "fan" -> {
            icon = Icons.Filled.ModeFanOff
            iconTint = GreenOk
            profileName = "Quạt điện"
        }
        else -> {
            icon = Icons.Filled.Lightbulb
            iconTint = OrangeWarning
            profileName = "Công tắc đèn"
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = !isPairing, onClick = onSelect),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
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
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = iconTint.copy(alpha = 0.15f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
                    }
                }

                Column {
                    Text(
                        text = device.name.ifBlank { device.device_id },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "${device.device_id} • $profileName",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (hasKey) {
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = GreenOk.copy(alpha = 0.15f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(Icons.Filled.Key, contentDescription = null, tint = GreenOk, modifier = Modifier.size(10.dp))
                        Text("Khóa OK", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = GreenOk)
                    }
                }
            }
        }
    }
}
