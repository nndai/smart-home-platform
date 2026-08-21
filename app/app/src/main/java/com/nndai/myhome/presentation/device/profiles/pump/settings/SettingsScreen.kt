package com.nndai.myhome.presentation.device.profiles.pump.settings

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nndai.myhome.R
import com.nndai.myhome.core.theme.CyanBlue
import com.nndai.myhome.core.theme.GreenOk
import com.nndai.myhome.core.theme.OrangeWarning
import com.nndai.myhome.core.theme.RedError
import com.nndai.myhome.presentation.device.common.settings.DeviceActionsCard
import com.nndai.myhome.presentation.device.common.settings.NetworkConnectionModeCard
import com.nndai.myhome.presentation.device.common.settings.SysLogSettingsCard
import com.nndai.myhome.presentation.device.common.settings.WifiScanDialog
import com.nndai.myhome.presentation.device.components.CompactTextField
import com.nndai.myhome.presentation.device.components.ConfirmDialog

@Composable
fun SettingsScreen(
    snackbarHostState: SnackbarHostState,
    viewModel: SettingsViewModel = viewModel()
) {
    val config by viewModel.deviceConfig.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val isRebooting by viewModel.isRebooting.collectAsStateWithLifecycle()
    val showRebootPrompt by viewModel.showRebootPrompt.collectAsStateWithLifecycle()

    val isScanningWifi by viewModel.isScanningWifi.collectAsStateWithLifecycle()
    val wifiNetworks by viewModel.wifiNetworks.collectAsStateWithLifecycle()
    val showWifiScanDialog by viewModel.showWifiScanDialog.collectAsStateWithLifecycle()

    // Form states - Network
    var connMode by remember(config) { mutableIntStateOf(config?.connMode ?: 1) }
    var wifiSSID by remember(config) { mutableStateOf(config?.wifiSSID ?: "") }
    var wifiPass by remember(config) { mutableStateOf(config?.wifiPass ?: "") }
    var isWifiOpen by remember { mutableStateOf(false) }

    var debugSSID by remember(config) { mutableStateOf(config?.debugSSID ?: "") }
    var debugPass by remember(config) { mutableStateOf(config?.debugPass ?: "") }

    var sysLogFileEnabled by remember(config) { mutableStateOf(config?.sysLogFileEnabled ?: false) }
    var sysLogFileLevel by remember(config) { mutableStateOf(config?.sysLogFileLevel?.toString() ?: "0") }

    // Form states - Pump Specific
    var pumpMode by remember(config) { mutableStateOf(config?.pumpMode ?: true) }
    var threshOff by remember(config) { mutableStateOf(config?.threshOff?.toString() ?: "100") }
    var threshDry by remember(config) { mutableStateOf(config?.threshNoWater?.toString() ?: "2000") }
    var threshRunning by remember(config) { mutableStateOf(config?.threshRunning?.toString() ?: "5000") }
    var threshOverload by remember(config) { mutableStateOf(config?.threshOverload?.toString() ?: "20000") }
    var dryTimeout by remember(config) { mutableStateOf(config?.dryTimeout?.toString() ?: "7000") }
    var overloadTimeout by remember(config) { mutableStateOf(config?.overloadTimeout?.toString() ?: "1000") }
    var relayStartMode by remember(config) { mutableIntStateOf(config?.relayStartMode ?: 0) }

    // Form states - Calibration
    var realAmps by remember { mutableStateOf("") }
    var realVolts by remember { mutableStateOf("") }
    var realWatts by remember { mutableStateOf("") }

    // Dialog states
    var showRebootDialog by remember { mutableStateOf(false) }
    var showFactoryResetDialog by remember { mutableStateOf(false) }
    var showResetCalibDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Pump Operation Mode Card (Pump vs Normal Relay)
        PumpModeCard(
            pumpMode = pumpMode,
            onPumpModeChange = { newMode ->
                pumpMode = newMode
                viewModel.saveConfig(mapOf("pumpMode" to newMode))
            }
        )

        // 2. Pump Protection & Thresholds Card
        AnimatedVisibility(visible = pumpMode) {
            PumpProtectionCard(
                threshOff = threshOff, onThreshOffChange = { threshOff = it },
                threshDry = threshDry, onThreshDryChange = { threshDry = it },
                threshRunning = threshRunning, onThreshRunningChange = { threshRunning = it },
                threshOverload = threshOverload, onThreshOverloadChange = { threshOverload = it },
                dryTimeout = dryTimeout, onDryTimeoutChange = { dryTimeout = it },
                overloadTimeout = overloadTimeout, onOverloadTimeoutChange = { overloadTimeout = it },
                relayStartMode = relayStartMode, onRelayStartModeChange = { relayStartMode = it },
                onSaveProtection = {
                    val updates = mutableMapOf<String, Any>()
                    threshOff.toIntOrNull()?.let { updates["threshOff"] = it }
                    threshDry.toIntOrNull()?.let { updates["threshNoWater"] = it }
                    threshRunning.toIntOrNull()?.let { updates["threshRunning"] = it }
                    threshOverload.toIntOrNull()?.let { updates["threshOverload"] = it }
                    dryTimeout.toIntOrNull()?.let { updates["dryTimeout"] = it }
                    overloadTimeout.toIntOrNull()?.let { updates["overloadTimeout"] = it }
                    updates["relayStartMode"] = relayStartMode
                    viewModel.saveConfig(updates)
                },
                isSaving = isSaving
            )
        }

        // 3. Calibration Card
        PumpCalibrationCard(
            realAmps = realAmps, onRealAmpsChange = { realAmps = it },
            realVolts = realVolts, onRealVoltsChange = { realVolts = it },
            realWatts = realWatts, onRealWattsChange = { realWatts = it },
            onCalibrateClick = {
                val calibData = mutableMapOf<String, Any>()
                realAmps.toFloatOrNull()?.let { calibData["real_i"] = it }
                realVolts.toFloatOrNull()?.let { calibData["real_v"] = it }
                realWatts.toFloatOrNull()?.let { calibData["real_p"] = it }
                if (calibData.isNotEmpty()) {
                    viewModel.calibrate(calibData)
                    realAmps = ""
                    realVolts = ""
                    realWatts = ""
                }
            },
            onResetCalibClick = { showResetCalibDialog = true },
            isSaving = isSaving
        )

        // 4. Shared Network Connection Mode Card (STA / AP / DEBUG)
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
            onScanWifiClick = { viewModel.scanWifi() },
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
                viewModel.saveConfig(updates)
            },
            isSaving = isSaving
        )

        // 5. Shared SysLog Settings Card
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
                viewModel.saveConfig(updates)
            },
            isSaving = isSaving
        )

        // 6. Shared Device Actions Card (Reboot / Factory Reset)
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
        onDismiss = { viewModel.dismissWifiScanDialog() }
    )

    // Reboot Confirmation Dialog
    if (showRebootDialog || showRebootPrompt) {
        ConfirmDialog(
            title = "Khởi động lại thiết bị",
            message = if (showRebootPrompt) "Cấu hình vừa thay đổi cần khởi động lại để áp dụng. Khởi động lại ngay?" else "Bạn có muốn khởi động lại thiết bị không?",
            confirmText = "Khởi động lại",
            onConfirm = {
                showRebootDialog = false
                viewModel.reboot()
            },
            onDismiss = {
                showRebootDialog = false
                viewModel.dismissRebootPrompt()
            }
        )
    }

    // Factory Reset Confirmation Dialog
    if (showFactoryResetDialog) {
        ConfirmDialog(
            title = "Khôi phục cài đặt gốc",
            message = "Thao tác này sẽ xóa toàn bộ cấu hình WiFi, hiệu chuẩn và thiết lập trên thiết bị. Thiết bị sẽ trở về trạng thái ban đầu.",
            confirmText = "Khôi phục gốc",
            isDangerous = true,
            onConfirm = {
                showFactoryResetDialog = false
                viewModel.factoryReset()
            },
            onDismiss = { showFactoryResetDialog = false }
        )
    }

    // Reset Calibration Confirmation Dialog
    if (showResetCalibDialog) {
        ConfirmDialog(
            title = "Khôi phục hiệu chuẩn mặc định",
            message = "Bạn có chắc chắn muốn đặt lại các hệ số hiệu chuẩn dòng điện, điện áp và công suất về mặc định không?",
            confirmText = "Đặt lại",
            isDangerous = true,
            onConfirm = {
                showResetCalibDialog = false
                viewModel.resetCalibration()
            },
            onDismiss = { showResetCalibDialog = false }
        )
    }
}

@Composable
private fun PumpModeCard(
    pumpMode: Boolean,
    onPumpModeChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
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
                            imageVector = Icons.Filled.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Text(
                    text = "Chế độ hoạt động thiết bị",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Pump Mode Pill
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onPumpModeChange(true) },
                    shape = MaterialTheme.shapes.small,
                    color = if (pumpMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    border = if (pumpMode) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Filled.WaterDrop, contentDescription = null, tint = CyanBlue, modifier = Modifier.size(18.dp))
                            Text(
                                text = "Chế độ Máy Bơm",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (pumpMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = "Bảo vệ thông minh (Cạn nước, Quá tải)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Normal Switch Mode Pill
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onPumpModeChange(false) },
                    shape = MaterialTheme.shapes.small,
                    color = if (!pumpMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    border = if (!pumpMode) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Filled.Power, contentDescription = null, tint = OrangeWarning, modifier = Modifier.size(18.dp))
                            Text(
                                text = "Rơ-le thường",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (!pumpMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = "Bật/tắt thuần túy, không ngắt bảo vệ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PumpProtectionCard(
    threshOff: String, onThreshOffChange: (String) -> Unit,
    threshDry: String, onThreshDryChange: (String) -> Unit,
    threshRunning: String, onThreshRunningChange: (String) -> Unit,
    threshOverload: String, onThreshOverloadChange: (String) -> Unit,
    dryTimeout: String, onDryTimeoutChange: (String) -> Unit,
    overloadTimeout: String, onOverloadTimeoutChange: (String) -> Unit,
    relayStartMode: Int, onRelayStartModeChange: (Int) -> Unit,
    onSaveProtection: () -> Unit,
    isSaving: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
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
                        Icon(Icons.Filled.ElectricBolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                }
                Text(
                    text = "Ngưỡng bảo vệ & Thời gian ngắt",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Current Thresholds
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactTextField(
                    value = threshOff, onValueChange = onThreshOffChange,
                    label = "Ngưỡng Tắt (mA)", isNumber = true, modifier = Modifier.weight(1f)
                )
                CompactTextField(
                    value = threshDry, onValueChange = onThreshDryChange,
                    label = "Cạn nước (mA)", isNumber = true, modifier = Modifier.weight(1f)
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactTextField(
                    value = threshRunning, onValueChange = onThreshRunningChange,
                    label = "Chạy bình thường (mA)", isNumber = true, modifier = Modifier.weight(1f)
                )
                CompactTextField(
                    value = threshOverload, onValueChange = onThreshOverloadChange,
                    label = "Quá tải (mA)", isNumber = true, modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

            // Timeouts
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactTextField(
                    value = dryTimeout, onValueChange = onDryTimeoutChange,
                    label = "Timeout Cạn nước (ms)", isNumber = true, modifier = Modifier.weight(1f)
                )
                CompactTextField(
                    value = overloadTimeout, onValueChange = onOverloadTimeoutChange,
                    label = "Timeout Quá tải (ms)", isNumber = true, modifier = Modifier.weight(1f)
                )
            }

            // Relay Start Mode Pills
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Trạng thái Relay sau khi cắm nguồn",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("0: Luôn Tắt" to 0, "1: Luôn Bật" to 1, "2: Nhớ trước đó" to 2).forEach { (label, mode) ->
                        val isSelected = relayStartMode == mode
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(MaterialTheme.shapes.extraSmall)
                                .clickable { onRelayStartModeChange(mode) },
                            shape = MaterialTheme.shapes.extraSmall,
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                        ) {
                            Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                Text(label, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }

            Button(
                onClick = onSaveProtection,
                enabled = !isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = MaterialTheme.shapes.small
            ) {
                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Lưu cấu hình bảo vệ", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PumpCalibrationCard(
    realAmps: String, onRealAmpsChange: (String) -> Unit,
    realVolts: String, onRealVoltsChange: (String) -> Unit,
    realWatts: String, onRealWattsChange: (String) -> Unit,
    onCalibrateClick: () -> Unit,
    onResetCalibClick: () -> Unit,
    isSaving: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
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
                        Icon(Icons.Filled.Build, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                }
                Text(
                    text = "Hiệu chuẩn cảm biến (Calibration)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactTextField(
                    value = realAmps, onValueChange = onRealAmpsChange,
                    label = "Dòng điện thực (A)", isNumber = true, modifier = Modifier.weight(1f)
                )
                CompactTextField(
                    value = realVolts, onValueChange = onRealVoltsChange,
                    label = "Điện áp thực (V)", isNumber = true, modifier = Modifier.weight(1f)
                )
                CompactTextField(
                    value = realWatts, onValueChange = onRealWattsChange,
                    label = "Công suất thực (W)", isNumber = true, modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onResetCalibClick,
                    enabled = !isSaving,
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp),
                    shape = MaterialTheme.shapes.small
                ) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Đặt lại calib", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onCalibrateClick,
                    enabled = !isSaving && (realAmps.isNotBlank() || realVolts.isNotBlank() || realWatts.isNotBlank()),
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp),
                    shape = MaterialTheme.shapes.small
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Hiệu chuẩn", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
