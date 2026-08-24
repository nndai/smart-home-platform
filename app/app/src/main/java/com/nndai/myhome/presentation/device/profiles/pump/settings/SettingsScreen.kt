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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.CircularProgressIndicator

private data class PendingFieldChange(
    val key: String,
    val displayName: String,
    val newValue: Any,
    val revertAction: () -> Unit
)

@Composable
fun SettingsScreen(
    snackbarHostState: SnackbarHostState,
    viewModel: SettingsViewModel = viewModel()
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val config by viewModel.deviceConfig.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val isRebooting by viewModel.isRebooting.collectAsStateWithLifecycle()
    val showRebootPrompt by viewModel.showRebootPrompt.collectAsStateWithLifecycle()

    // Localized display names for field-confirm dialogs
    val displayNameOffThreshold = stringResource(R.string.ps_label_display_name_off)
    val displayNameDryRun = stringResource(R.string.ps_label_display_name_dry)
    val displayNameRunning = stringResource(R.string.ps_label_display_name_running)
    val displayNameOverload = stringResource(R.string.ps_label_display_name_overload)
    val displayNameDryTimeout = stringResource(R.string.ps_label_display_name_dry_timeout)
    val displayNameOverloadTimeout = stringResource(R.string.ps_label_display_name_overload_timeout)

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
    var pendingSingleField by remember { mutableStateOf<PendingFieldChange?>(null) }

    var showModeDialog by remember { mutableStateOf<Boolean?>(null) }
    var showRelayModeDialog by remember { mutableStateOf<Int?>(null) }
    var showLogSwitchDialog by remember { mutableStateOf<Boolean?>(null) }
    var showLogLevelDialog by remember { mutableStateOf<String?>(null) }

    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { focusManager.clearFocus() })
            }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Pump Operation Mode Card (Pump vs Normal Relay)
        PumpModeCard(
            pumpMode = pumpMode,
            onPumpModeChange = { newMode ->
                if (newMode != pumpMode) showModeDialog = newMode
            }
        )

        // 2. Pump Protection & Thresholds Card
        PumpProtectionCard(
            pumpMode = pumpMode,
            threshOff = threshOff, onThreshOffChange = { threshOff = it },
            threshDry = threshDry, onThreshDryChange = { threshDry = it },
            threshRunning = threshRunning, onThreshRunningChange = { threshRunning = it },
            threshOverload = threshOverload, onThreshOverloadChange = { threshOverload = it },
            dryTimeout = dryTimeout, onDryTimeoutChange = { dryTimeout = it },
            overloadTimeout = overloadTimeout, onOverloadTimeoutChange = { overloadTimeout = it },
            relayStartMode = relayStartMode,
            onRelayStartModeChange = { newMode ->
                if (newMode != relayStartMode) showRelayModeDialog = newMode
            },
            onFieldFocusLost = { key, displayName, newValStr ->
                val newInt = newValStr.toIntOrNull()
                val oldInt = when(key) {
                    "threshOff" -> config?.threshOff ?: 100
                    "threshNoWater" -> config?.threshNoWater ?: 2000
                    "threshRunning" -> config?.threshRunning ?: 5000
                    "threshOverload" -> config?.threshOverload ?: 20000
                    "dryTimeout" -> config?.dryTimeout ?: 7000
                    "overloadTimeout" -> config?.overloadTimeout ?: 1000
                    else -> null
                }

                val revertAction: () -> Unit = {
                    when(key) {
                        "threshOff" -> threshOff = (config?.threshOff ?: 100).toString()
                        "threshNoWater" -> threshDry = (config?.threshNoWater ?: 2000).toString()
                        "threshRunning" -> threshRunning = (config?.threshRunning ?: 5000).toString()
                        "threshOverload" -> threshOverload = (config?.threshOverload ?: 20000).toString()
                        "dryTimeout" -> dryTimeout = (config?.dryTimeout ?: 7000).toString()
                        "overloadTimeout" -> overloadTimeout = (config?.overloadTimeout ?: 1000).toString()
                    }
                }

                if (newInt != null && newInt != oldInt) {
                    var isValid = true
                    if (key.startsWith("thresh")) {
                        val offVal = if (key == "threshOff") newInt else threshOff.toIntOrNull() ?: (config?.threshOff ?: 100)
                        val dryVal = if (key == "threshNoWater") newInt else threshDry.toIntOrNull() ?: (config?.threshNoWater ?: 2000)
                        val runningVal = if (key == "threshRunning") newInt else threshRunning.toIntOrNull() ?: (config?.threshRunning ?: 5000)
                        val overloadVal = if (key == "threshOverload") newInt else threshOverload.toIntOrNull() ?: (config?.threshOverload ?: 20000)

                        if (!(offVal < dryVal && dryVal < runningVal && runningVal < overloadVal)) {
                            isValid = false
                            viewModel.showMessage(context.getString(R.string.settings_threshold_order))
                            revertAction()
                        }
                    } else if (key.endsWith("Timeout")) {
                        if (newInt <= 0) {
                            isValid = false
                            viewModel.showMessage(context.getString(R.string.settings_timeout_positive))
                            revertAction()
                        }
                    }

                    if (isValid) {
                        pendingSingleField = PendingFieldChange(key, displayName, newInt, revertAction)
                    }
                } else if (newInt == null || newInt == oldInt) {
                    revertAction()
                }
            }
        )

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

    // Single Field Confirm Dialog
    pendingSingleField?.let { pending ->
        ConfirmDialog(
            title = stringResource(R.string.ps_save_config_title),
            message = stringResource(R.string.settings_confirm_save_message, pending.displayName, pending.newValue),
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                viewModel.saveConfig(mapOf(pending.key to pending.newValue))
                pendingSingleField = null
            },
            onDismiss = {
                pending.revertAction()
                pendingSingleField = null
            }
        )
    }

    // Mode Switch Confirm Dialog
    showModeDialog?.let { targetMode ->
        ConfirmDialog(
            title = stringResource(if (targetMode) R.string.settings_switch_pump_mode else R.string.settings_switch_switch_mode),
            message = if (targetMode)
                stringResource(R.string.settings_pump_mode_desc)
            else
                stringResource(R.string.settings_switch_mode_desc),
            confirmText = stringResource(R.string.rss_agree),
            isDangerous = !targetMode,
            onConfirm = {
                showModeDialog = null
                pumpMode = targetMode
                viewModel.saveConfig(mapOf("pumpMode" to targetMode))
            },
            onDismiss = { showModeDialog = null }
        )
    }

    // Relay Mode Confirm Dialog
    showRelayModeDialog?.let { targetMode ->
        val modeText = when (targetMode) {
            0 -> stringResource(R.string.settings_relay_off)
            1 -> stringResource(R.string.settings_relay_on)
            else -> stringResource(R.string.settings_relay_keep_last)
        }
        ConfirmDialog(
            title = stringResource(R.string.settings_change_relay_mode),
            message = context.getString(R.string.settings_relay_mode_message, modeText),
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                showRelayModeDialog = null
                relayStartMode = targetMode
                viewModel.saveConfig(mapOf("relayStartMode" to targetMode))
            },
            onDismiss = { showRelayModeDialog = null }
        )
    }

    // SysLog Switch Confirm Dialog
    showLogSwitchDialog?.let { targetState ->
        ConfirmDialog(
            title = stringResource(if (targetState) R.string.rss_syslog_on_title else R.string.rss_syslog_off_title),
            message = stringResource(if (targetState) R.string.ps_syslog_on_msg else R.string.ps_syslog_off_msg),
            confirmText = stringResource(R.string.rss_agree),
            onConfirm = {
                showLogSwitchDialog = null
                sysLogFileEnabled = targetState
                viewModel.saveConfig(mapOf("sysLogFileEnabled" to targetState))
            },
            onDismiss = { showLogSwitchDialog = null }
        )
    }

    // SysLog Level Confirm Dialog
    showLogLevelDialog?.let { targetLevel ->
        val levelName = when (targetLevel) {
            "0" -> context.getString(R.string.rss_loglevel_trace)
            "1" -> context.getString(R.string.rss_loglevel_debug)
            "2" -> context.getString(R.string.rss_loglevel_info)
            "3" -> context.getString(R.string.rss_loglevel_warn)
            "4" -> context.getString(R.string.rss_loglevel_error)
            "5" -> context.getString(R.string.rss_loglevel_fatal)
            else -> context.getString(R.string.rss_loglevel_custom, targetLevel)
        }
        ConfirmDialog(
            title = context.getString(R.string.rss_loglevel_change_title),
            message = context.getString(R.string.rss_loglevel_change_msg, levelName),
            confirmText = stringResource(R.string.rss_agree),
            onConfirm = {
                showLogLevelDialog = null
                sysLogFileLevel = targetLevel
                viewModel.saveConfig(mapOf("sysLogFileLevel" to (targetLevel.toIntOrNull() ?: 0)))
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
                viewModel.reboot()
            },
            onDismiss = {
                showRebootDialog = false
                viewModel.dismissRebootPrompt()
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
            message = stringResource(R.string.ps_factory_message),
            confirmText = stringResource(R.string.cs_factory_reset),
            isDangerous = true,
            requiredInput = "reset",
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
            title = stringResource(R.string.settings_reset_calibration_title),
            message = stringResource(R.string.settings_reset_calibration_msg),
            confirmText = stringResource(R.string.settings_reset_to_default),
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
                    text = stringResource(R.string.ps_device_mode_section),
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
                        .height(72.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onPumpModeChange(true) },
                    shape = MaterialTheme.shapes.medium,
                    color = if (pumpMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (pumpMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    border = if (pumpMode) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
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
                                text = stringResource(R.string.settings_pump_mode),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (pumpMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = stringResource(R.string.ps_pump_mode_protect_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Normal Switch Mode Pill
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(72.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onPumpModeChange(false) },
                    shape = MaterialTheme.shapes.medium,
                    color = if (!pumpMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (!pumpMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    border = if (!pumpMode) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
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
                                text = stringResource(R.string.ps_manual_switch_label),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (!pumpMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = stringResource(R.string.ps_pure_toggle_desc),
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
    pumpMode: Boolean,
    threshOff: String, onThreshOffChange: (String) -> Unit,
    threshDry: String, onThreshDryChange: (String) -> Unit,
    threshRunning: String, onThreshRunningChange: (String) -> Unit,
    threshOverload: String, onThreshOverloadChange: (String) -> Unit,
    dryTimeout: String, onDryTimeoutChange: (String) -> Unit,
    overloadTimeout: String, onOverloadTimeoutChange: (String) -> Unit,
    relayStartMode: Int, onRelayStartModeChange: (Int) -> Unit,
    onFieldFocusLost: (key: String, displayName: String, newValStr: String) -> Unit
) {
    val displayNameOffThreshold = stringResource(R.string.ps_label_display_name_off)
    val displayNameDryRun = stringResource(R.string.ps_label_display_name_dry)
    val displayNameRunning = stringResource(R.string.ps_label_display_name_running)
    val displayNameOverload = stringResource(R.string.ps_label_display_name_overload)
    val displayNameDryTimeout = stringResource(R.string.ps_label_display_name_dry_timeout)
    val displayNameOverloadTimeout = stringResource(R.string.ps_label_display_name_overload_timeout)
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
                    text = stringResource(R.string.ps_thresholds_timeouts_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Current Thresholds
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactTextField(
                    value = threshOff, onValueChange = onThreshOffChange,
                    label = stringResource(R.string.ps_label_off_threshold_ma), isNumber = true, modifier = Modifier.weight(1f),
                    onFocusLost = { onFieldFocusLost("threshOff", displayNameOffThreshold, threshOff) }
                )
                CompactTextField(
                    value = threshOverload, onValueChange = onThreshOverloadChange,
                    label = stringResource(R.string.ps_label_overload_ma), isNumber = true, modifier = Modifier.weight(1f),
                    onFocusLost = { onFieldFocusLost("threshOverload", displayNameOverload, threshOverload) }
                )
            }

            if (pumpMode) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactTextField(
                        value = threshDry, onValueChange = onThreshDryChange,
                        label = stringResource(R.string.ps_label_dry_ma), isNumber = true, modifier = Modifier.weight(1f),
                        onFocusLost = { onFieldFocusLost("threshNoWater", displayNameDryRun, threshDry) }
                    )
                    CompactTextField(
                        value = threshRunning, onValueChange = onThreshRunningChange,
                        label = stringResource(R.string.ps_label_running_ma), isNumber = true, modifier = Modifier.weight(1f),
                        onFocusLost = { onFieldFocusLost("threshRunning", displayNameRunning, threshRunning) }
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

            // Timeouts
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pumpMode) {
                    CompactTextField(
                        value = dryTimeout, onValueChange = onDryTimeoutChange,
                        label = stringResource(R.string.ps_label_dry_timeout_ms), isNumber = true, modifier = Modifier.weight(1f),
                        onFocusLost = { onFieldFocusLost("dryTimeout", displayNameDryTimeout, dryTimeout) }
                    )
                }
                CompactTextField(
                    value = overloadTimeout, onValueChange = onOverloadTimeoutChange,
                    label = stringResource(R.string.ps_label_overload_timeout_ms), isNumber = true, modifier = Modifier.weight(1f),
                    onFocusLost = { onFieldFocusLost("overloadTimeout", displayNameOverloadTimeout, overloadTimeout) }
                )
                if (!pumpMode) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }

            // Relay Start Mode Pills
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.settings_relay_startup_mode),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(stringResource(R.string.settings_relay_off) to 0, stringResource(R.string.settings_relay_on) to 1, stringResource(R.string.settings_relay_keep_last) to 2).forEach { (label, mode) ->
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
                            Box(modifier = Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                                Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }
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
                    text = stringResource(R.string.settings_calibration_coefficients),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactTextField(
                    value = realAmps, onValueChange = onRealAmpsChange,
                    label = stringResource(R.string.ps_label_current_a), isNumber = true, modifier = Modifier.weight(1f)
                )
                CompactTextField(
                    value = realVolts, onValueChange = onRealVoltsChange,
                    label = stringResource(R.string.ps_label_voltage_v), isNumber = true, modifier = Modifier.weight(1f)
                )
                CompactTextField(
                    value = realWatts, onValueChange = onRealWattsChange,
                    label = stringResource(R.string.ps_label_power_w), isNumber = true, modifier = Modifier.weight(1f)
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
                    Text(stringResource(R.string.settings_reset_to_default), fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                    Text(stringResource(R.string.ps_calibrate_action), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
