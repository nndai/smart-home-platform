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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nndai.myhome.R
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
import com.nndai.myhome.data.remote.WifiNetwork

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
    val context = LocalContext.current

    // Form states
    var connMode by remember(config) { mutableIntStateOf(config?.connMode ?: 1) }
    var wifiSSID by remember(config) { mutableStateOf(config?.wifiSSID ?: "") }
    var wifiPass by remember(config) { mutableStateOf(config?.wifiPass ?: "") }

    // Track initial values to detect what actually changed
    val initialConnMode by remember(config) { mutableIntStateOf(config?.connMode ?: 1) }
    val initialWifiSSID by remember(config) { mutableStateOf(config?.wifiSSID ?: "") }
    val initialWifiPass by remember(config) { mutableStateOf(config?.wifiPass ?: "") }
    // True when user explicitly edited the password (typed something different from the masked value)
    var wifiPassEdited by remember(config) { mutableStateOf(false) }

    // Keep last scanned networks to derive open/encrypted status
    var lastScannedNetworks by remember { mutableStateOf<List<WifiNetwork>>(emptyList()) }

    // Derive isWifiOpen from current SSID vs scanned list (only true if matched scanned network is open)
    val matchedNetwork = lastScannedNetworks.firstOrNull { it.ssid == wifiSSID.trim() }
    val isWifiOpen = matchedNetwork != null && !matchedNetwork.isEncrypt

    var debugSSID by remember(config) { mutableStateOf(config?.debugSSID ?: "") }
    var debugPass by remember(config) { mutableStateOf(config?.debugPass ?: "") }

    val initialDebugSSID by remember(config) { mutableStateOf(config?.debugSSID ?: "") }
    val initialDebugPass by remember(config) { mutableStateOf(config?.debugPass ?: "") }
    var debugPassEdited by remember(config) { mutableStateOf(false) }

    var sysLogFileEnabled by remember(config) { mutableStateOf(config?.sysLogFileEnabled ?: false) }
    var sysLogFileLevel by remember(config) { mutableStateOf(config?.sysLogFileLevel?.toString() ?: "0") }

    // Dialogs
    var showTargetPickerSheet by remember { mutableStateOf(false) }
    var showClearTargetDialog by remember { mutableStateOf(false) }
    var showRebootDialog by remember { mutableStateOf(false) }
    var showFactoryResetDialog by remember { mutableStateOf(false) }
    var showLogSwitchDialog by remember { mutableStateOf<Boolean?>(null) }
    var showLogLevelDialog by remember { mutableStateOf<String?>(null) }
    // Pending network config changes awaiting user confirmation
    var pendingNetworkUpdates by remember { mutableStateOf<Map<String, Any>?>(null) }

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
            onWifiPassChange = {
                wifiPass = it
                // Mark password as edited only if the new value differs from initial
                wifiPassEdited = (it != initialWifiPass)
            },
            isWifiOpen = isWifiOpen,
            onWifiOpenChange = { /* derived, no-op */ },
            debugSSID = debugSSID,
            onDebugSSIDChange = { debugSSID = it },
            debugPass = debugPass,
            onDebugPassChange = {
                debugPass = it
                debugPassEdited = (it != initialDebugPass)
            },
            isScanningWifi = isScanningWifi,
            onScanWifiClick = { settingsViewModel.scanWifi() },
            onSaveNetworkClick = {
                val updates = mutableMapOf<String, Any>()
                // Always include connMode if it changed
                if (connMode != initialConnMode) {
                    updates["connMode"] = connMode
                }
                if (connMode == 1) {
                    val matched = lastScannedNetworks.firstOrNull { it.ssid == wifiSSID.trim() }
                    if (matched != null) {
                        // Mạng có trong danh sách quét
                        if (matched.isEncrypt) {
                            // Mạng bắt buộc mật khẩu: nếu sửa pass thì phải đủ 8-64 ký tự
                            if (wifiPassEdited && wifiPass.length !in 8..64) {
                                settingsViewModel.showMessage(context.getString(R.string.settings_wifi_pass_length))
                                return@NetworkConnectionModeCard
                            }
                        }
                    } else {
                        // Mạng nhập tay (không có trong danh sách quét):
                        // Không nhập pass -> OK (mạng open). Nếu đã nhập pass -> phải đủ 8-64 ký tự
                        if (wifiPassEdited && wifiPass.isNotEmpty() && wifiPass.length !in 8..64) {
                            settingsViewModel.showMessage(context.getString(R.string.settings_wifi_pass_length))
                            return@NetworkConnectionModeCard
                        }
                    }

                    // Validate SSID when changed
                    if (wifiSSID != initialWifiSSID) {
                        if (wifiSSID.trim().isEmpty()) {
                            settingsViewModel.showMessage(context.getString(R.string.settings_wifi_ssid_empty))
                            return@NetworkConnectionModeCard
                        }
                        updates["wifiSSID"] = wifiSSID.trim()
                    }
                    // Only include password if user explicitly edited it
                    if (wifiPassEdited) {
                        if (matched != null && !matched.isEncrypt) {
                            updates["wifiPass"] = ""
                        } else {
                            updates["wifiPass"] = wifiPass
                        }
                    }
                } else if (connMode == 2) {
                    if (debugSSID != initialDebugSSID) {
                        if (debugSSID.trim().isEmpty()) {
                            settingsViewModel.showMessage(context.getString(R.string.settings_wifi_ssid_empty))
                            return@NetworkConnectionModeCard
                        }
                        updates["debugSSID"] = debugSSID.trim()
                    }
                    if (debugPassEdited && debugPass.isNotEmpty() && debugPass.length !in 8..64) {
                        settingsViewModel.showMessage(context.getString(R.string.settings_wifi_pass_length))
                        return@NetworkConnectionModeCard
                    }
                    if (debugPassEdited) {
                        updates["debugPass"] = debugPass
                    }
                }
                if (updates.isEmpty()) {
                    settingsViewModel.showMessage(context.getString(R.string.cs_msg_no_changes))
                } else {
                    pendingNetworkUpdates = updates
                }
            },
            isSaving = isSaving
        )

        // 3. SysLog Settings Card (Shared)
        SysLogSettingsCard(
            sysLogFileEnabled = sysLogFileEnabled,
            onSysLogFileEnabledChange = { newState ->
                if (newState != sysLogFileEnabled) showLogSwitchDialog = newState
            },
            sysLogFileLevel = sysLogFileLevel,
            onSysLogFileLevelChange = { newLevel ->
                if (newLevel != sysLogFileLevel) {
                    showLogLevelDialog = newLevel
                }
            }
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
            // Selecting from scan always clears password and marks it as edited
            wifiPass = ""
            wifiPassEdited = true
            // Persist scanned networks for open/encrypted validation
            lastScannedNetworks = wifiNetworks
        },
        onDismiss = {
            // Persist networks even when dismissing dialog
            if (wifiNetworks.isNotEmpty()) lastScannedNetworks = wifiNetworks
            settingsViewModel.dismissWifiScanDialog()
        }
    )

    // Network Save Confirm Dialog
    pendingNetworkUpdates?.let { updates ->
        val summary = updates.entries.joinToString("\n") { (key, value) ->
            when (key) {
                "connMode" -> "• ${context.getString(R.string.cs_network_mode)}: ${when (value) {
                    0 -> context.getString(R.string.cs_ap_hotspot)
                    1 -> context.getString(R.string.settings_wifi_mqtt)
                    2 -> context.getString(R.string.settings_wifi_debug)
                    else -> value.toString()
                }}"
                "wifiSSID" -> "• ${context.getString(R.string.cs_label_wifi_ssid)}: $value"
                "wifiPass" -> "• ${context.getString(R.string.cs_label_wifi_password)}: $value"
                "debugSSID" -> "• ${context.getString(R.string.cs_label_debug_ssid)}: $value"
                "debugPass" -> "• ${context.getString(R.string.cs_label_debug_password)}: $value"
                else -> "• $key: $value"
            }
        }
        ConfirmDialog(
            title = stringResource(R.string.cs_confirm_network_title),
            message = stringResource(R.string.cs_confirm_network_msg, summary),
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                settingsViewModel.saveConfig(updates)
                pendingNetworkUpdates = null
            },
            onDismiss = { pendingNetworkUpdates = null }
        )
    }

    // Clear Target Confirmation Dialog
    if (showClearTargetDialog) {
        ConfirmDialog(
            title = stringResource(R.string.rss_unlink_title),
            message = stringResource(R.string.rss_unlink_message),
            confirmText = stringResource(R.string.rss_unlink_action),
            isDangerous = true,
            onConfirm = {
                showClearTargetDialog = false
                remoteSwitchViewModel.clearTarget()
            },
            onDismiss = { showClearTargetDialog = false }
        )
    }
    
    // SysLog Switch Confirm Dialog
    showLogSwitchDialog?.let { targetState ->
        ConfirmDialog(
            title = stringResource(if (targetState) R.string.rss_syslog_on_title else R.string.rss_syslog_off_title),
            message = stringResource(if (targetState) R.string.rss_syslog_on_msg else R.string.rss_syslog_off_msg),
            confirmText = stringResource(R.string.rss_agree),
            onConfirm = {
                showLogSwitchDialog = null
                sysLogFileEnabled = targetState
                settingsViewModel.saveConfig(mapOf("sysLogFileEnabled" to targetState))
            },
            onDismiss = { showLogSwitchDialog = null }
        )
    }

    // SysLog Level Confirm Dialog
    showLogLevelDialog?.let { targetLevel ->
        val levelName = when (targetLevel) {
            "0" -> stringResource(R.string.rss_loglevel_trace)
            "1" -> stringResource(R.string.rss_loglevel_debug)
            "2" -> stringResource(R.string.rss_loglevel_info)
            "3" -> stringResource(R.string.rss_loglevel_warn)
            "4" -> stringResource(R.string.rss_loglevel_error)
            "5" -> stringResource(R.string.rss_loglevel_fatal)
            else -> context.getString(R.string.rss_loglevel_custom, targetLevel)
        }
        ConfirmDialog(
            title = context.getString(R.string.rss_loglevel_change_title),
            message = context.getString(R.string.rss_loglevel_change_msg, levelName),
            confirmText = stringResource(R.string.rss_agree),
            onConfirm = {
                showLogLevelDialog = null
                sysLogFileLevel = targetLevel
                settingsViewModel.saveConfig(mapOf("sysLogFileLevel" to (targetLevel.toIntOrNull() ?: 0)))
            },
            onDismiss = { showLogLevelDialog = null }
        )
    }

    // Reboot Confirmation Dialog
    if (showRebootDialog || showRebootPrompt) {
        ConfirmDialog(
            title = stringResource(R.string.rss_reboot_title),
            message = stringResource(if (showRebootPrompt) R.string.rss_reboot_pending_msg else R.string.rss_reboot_ask_msg),
            confirmText = stringResource(R.string.settings_reboot_now),
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

    // Saving Spinner Dialog
    if (isSaving) {
        Dialog(onDismissRequest = {}) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp))
                    Text(
                        text = stringResource(R.string.cs_saving_config),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }

    // Rebooting Spinner Dialog
    if (isRebooting) {
        Dialog(onDismissRequest = {}) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp))
                    Text(
                        text = stringResource(R.string.rss_rebooting),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }

    // Factory Reset Confirmation Dialog
    if (showFactoryResetDialog) {
        ConfirmDialog(
            title = stringResource(R.string.rss_factory_title),
            message = stringResource(R.string.rss_factory_message),
            confirmText = stringResource(R.string.cs_factory_reset),
            isDangerous = true,
            requiredInput = "reset",
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
                        text = stringResource(R.string.rs_sheet_pick_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = stringResource(R.string.rs_sheet_scope_desc_short),
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
                                text = stringResource(R.string.rs_no_devices),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.rs_pair_hint),
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
                    text = stringResource(R.string.rss_target_config_title),
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
                            text = stringResource(R.string.rss_device_type, if (targetType.equals("pump", true)) stringResource(R.string.rs_profile_pump) else stringResource(R.string.rs_profile_switch)),
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
                        Text(stringResource(R.string.rs_change_target), fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                        Text(stringResource(R.string.rs_clear_target_action), color = RedError, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                            text = stringResource(R.string.rss_no_target_linked),
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
                    Text(if (isPairing) stringResource(R.string.rss_picking_target) else stringResource(R.string.rss_pick_target), fontWeight = FontWeight.Bold)
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
    val context = LocalContext.current
    val profile = device.profile.lowercase()
    val icon: ImageVector
    val iconTint: Color
    val profileName: String

    when (profile) {
        "pump" -> {
            icon = Icons.Filled.WaterDrop
            iconTint = CyanBlue
            profileName = context.getString(R.string.rs_profile_pump)
        }
        "fan" -> {
            icon = Icons.Filled.ModeFanOff
            iconTint = GreenOk
            profileName = context.getString(R.string.rs_profile_fan)
        }
        else -> {
            icon = Icons.Filled.Lightbulb
            iconTint = OrangeWarning
            profileName = context.getString(R.string.rs_profile_switch)
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
                        Text(stringResource(R.string.rs_key_ok), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = GreenOk)
                    }
                }
            }
        }
    }
}
