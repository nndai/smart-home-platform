package com.nndai.myhome.presentation.device.profiles.pump.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nndai.myhome.BuildConfig
import com.nndai.myhome.R
import com.nndai.myhome.data.di.PumpRepositoryProvider
import com.nndai.myhome.presentation.device.components.CompactTextField
import com.nndai.myhome.presentation.device.components.ConfirmDialog
import com.nndai.myhome.presentation.device.components.SectionHeader
import kotlinx.coroutines.delay

private data class PendingFieldChange(
    val key: String,
    val displayName: String,
    val newValue: Any,
    val revertAction: () -> Unit
)

@Composable
private fun WifiSignalBars(
    rssi: Int,
    modifier: Modifier = Modifier
) {
    val activeBars = when {
        rssi >= -55 -> 5
        rssi >= -67 -> 4
        rssi >= -78 -> 3
        rssi >= -88 -> 2
        else -> 1
    }

    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)

    Row(
        modifier = modifier.height(14.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        for (i in 1..5) {
            val barHeightFraction = i / 5f
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight(barHeightFraction)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(if (i <= activeBars) activeColor else inactiveColor)
            )
        }
    }
}

private fun Modifier.drawVerticalScrollbar(
    scrollState: ScrollState,
    color: Color = Color.Gray,
    width: Dp = 5.dp
): Modifier = drawWithContent {
    drawContent()
    if (scrollState.maxValue > 0) {
        val viewHeight = size.height
        val totalContentHeight = scrollState.maxValue + viewHeight
        val scrollbarHeight = (viewHeight * (viewHeight / totalContentHeight)).coerceAtLeast(32.dp.toPx())
        val scrollbarY = (scrollState.value.toFloat() / scrollState.maxValue.toFloat()) * (viewHeight - scrollbarHeight)

        drawRoundRect(
            color = color,
            topLeft = Offset(size.width - width.toPx(), scrollbarY),
            size = Size(width.toPx(), scrollbarHeight),
            cornerRadius = CornerRadius(width.toPx() / 2, width.toPx() / 2),
            alpha = 0.7f
        )
    }
}

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
    val context = LocalContext.current

    // Form states
    var connMode by remember(config) { mutableIntStateOf(config?.connMode ?: 0) }
    var pumpMode by remember(config) { mutableStateOf(config?.pumpMode ?: true) }
    var threshOff by remember(config) { mutableStateOf(config?.threshOff?.toString() ?: "100") }
    var threshDry by remember(config) { mutableStateOf(config?.threshNoWater?.toString() ?: "2000") }
    var threshRunning by remember(config) { mutableStateOf(config?.threshRunning?.toString() ?: "5000") }
    var threshOverload by remember(config) { mutableStateOf(config?.threshOverload?.toString() ?: "20000") }
    var dryTimeout by remember(config) { mutableStateOf(config?.dryTimeout?.toString() ?: "7000") }
    var overloadTimeout by remember(config) { mutableStateOf(config?.overloadTimeout?.toString() ?: "1000") }
    var relayStartMode by remember(config) { mutableIntStateOf(config?.relayStartMode ?: 0) }

    var wifiSSID by remember(config) { mutableStateOf(config?.wifiSSID ?: "") }
    var wifiPass by remember(config) { mutableStateOf(config?.wifiPass ?: "") }
    var isWifiOpen by remember { mutableStateOf(false) }

    var apSSID by remember(config) { mutableStateOf(config?.apSSID ?: "") }
    var apPass by remember(config) { mutableStateOf(config?.apPass ?: "") }

    var debugSSID by remember(config) { mutableStateOf(config?.debugSSID ?: "") }
    var debugPass by remember(config) { mutableStateOf(config?.debugPass ?: "") }

    var mqttServer by remember(config) { mutableStateOf(config?.mqttServer ?: "") }
    var mqttPort by remember(config) { mutableStateOf(config?.mqttPort?.toString() ?: "8883") }
    var mqttUser by remember(config) { mutableStateOf(config?.mqttUser ?: "") }
    var mqttPass by remember(config) { mutableStateOf(config?.mqttPass ?: "") }
    var mqttTopic by remember(config) { mutableStateOf(config?.mqttTopic ?: PumpRepositoryProvider.getMqttTopic()) }

    var cCal by remember(config) { mutableStateOf(config?.cCal?.toString() ?: "1.0") }
    var vCal by remember(config) { mutableStateOf(config?.vCal?.toString() ?: "1.0") }
    var pCal by remember(config) { mutableStateOf(config?.pCal?.toString() ?: "1.0") }

    var sysLogFileEnabled by remember(config) { mutableStateOf(config?.sysLogFileEnabled ?: false) }
    var sysLogFileLevel by remember(config) { mutableStateOf(config?.sysLogFileLevel?.toString() ?: "0") }

    // UI Expand/Collapse state
    var showOtherNetworkModes by remember { mutableStateOf(false) }

    // Dialog states
    var pendingSingleField by remember { mutableStateOf<PendingFieldChange?>(null) }

    var showNetworkConfirmDialog by remember { mutableStateOf(false) }
    var pendingNetworkUpdates by remember { mutableStateOf<Map<String, Any>>(emptyMap()) }
    var pendingLocalConnectionChanged by remember { mutableStateOf(false) }

    var showModeDialog by remember { mutableStateOf<Boolean?>(null) }
    var showRelayModeDialog by remember { mutableStateOf<Int?>(null) }
    var showLogSwitchDialog by remember { mutableStateOf<Boolean?>(null) }

    var showManualRebootDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    var showCalibCard by remember { mutableStateOf(false) }
    var realAmps by remember { mutableStateOf("") }
    var realVolts by remember { mutableStateOf("") }
    var realWatts by remember { mutableStateOf("") }

    var showCalibConfirmDialog by remember { mutableStateOf<Map<String, Any>?>(null) }
    var showResetCalibConfirmDialog by remember { mutableStateOf(false) }

    // Message collector
    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshConfig()
        while (true) {
            delay(5000L)
            if (viewModel.deviceConfig.value == null) {
                viewModel.refreshConfig()
            }
        }
    }

    val focusManager = LocalFocusManager.current

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { focusManager.clearFocus() })
                }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Device Mode
            SectionHeader(title = stringResource(R.string.settings_device_protection_mode))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // PUMP MODE
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.medium)
                        .clickable {
                            if (!pumpMode) {
                                showModeDialog = true
                            }
                        },
                    shape = MaterialTheme.shapes.medium,
                    color = if (pumpMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (pumpMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    border = if (pumpMode) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.WaterDrop,
                                contentDescription = null,
                                tint = if (pumpMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(R.string.settings_pump_mode),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = stringResource(R.string.settings_smart_protection_enabled),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (pumpMode) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }

                // SWITCH MODE
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.medium)
                        .clickable {
                            if (pumpMode) {
                                showModeDialog = false
                            }
                        },
                    shape = MaterialTheme.shapes.medium,
                    color = if (!pumpMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (!pumpMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    border = if (!pumpMode) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Power,
                                contentDescription = null,
                                tint = if (!pumpMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(R.string.settings_switch_mode),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = stringResource(R.string.settings_manual_switch_only),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (!pumpMode) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            // Network Connection Card
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_network_connection_mode),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            stringResource(R.string.settings_wifi_mqtt) to 1,
                            stringResource(R.string.settings_ap_hotspot) to 0,
                            stringResource(R.string.settings_wifi_debug) to 2
                        ).forEach { (label, modeIndex) ->
                            val selected = connMode == modeIndex
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable { connMode = modeIndex },
                                shape = MaterialTheme.shapes.small,
                                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.secondary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    // Mode 1: WiFi STA + MQTT
                    if (connMode == 1) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = stringResource(R.string.settings_wifi_sta_credentials),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                CompactTextField(
                                    value = wifiSSID, onValueChange = { wifiSSID = it },
                                    label = stringResource(R.string.settings_label_wifi_ssid),
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = { viewModel.scanWifi() },
                                    enabled = !isScanningWifi && config != null,
                                    modifier = Modifier
                                        .padding(top = 4.dp)
                                        .height(42.dp),
                                    shape = MaterialTheme.shapes.small,
                                    contentPadding = PaddingValues(horizontal = 10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                ) {
                                    if (isScanningWifi) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.size(4.dp))
                                        Text(stringResource(R.string.settings_scanning), style = MaterialTheme.typography.labelSmall)
                                    } else {
                                        Icon(
                                            imageVector = Icons.Filled.Wifi,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.size(4.dp))
                                        Text(stringResource(R.string.settings_scan), style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }

                            if (!isWifiOpen) {
                                CompactTextField(
                                    value = wifiPass, onValueChange = { wifiPass = it },
                                    label = stringResource(R.string.settings_label_wifi_password), isPassword = true
                                )
                            } else {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.LockOpen,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = stringResource(R.string.settings_open_network),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.settings_mqtt_server),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            CompactTextField(
                                value = mqttServer, onValueChange = { mqttServer = it },
                                label = stringResource(R.string.settings_label_mqtt_host)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CompactTextField(
                                    value = mqttUser, onValueChange = { mqttUser = it },
                                    label = stringResource(R.string.settings_label_username), modifier = Modifier.weight(1f)
                                )
                                CompactTextField(
                                    value = mqttPass, onValueChange = { mqttPass = it },
                                    label = stringResource(R.string.settings_label_password), isPassword = true, modifier = Modifier.weight(1f)
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CompactTextField(
                                    value = mqttTopic, onValueChange = { mqttTopic = it },
                                    label = stringResource(R.string.settings_label_topic), modifier = Modifier.weight(2f)
                                )
                                CompactTextField(
                                    value = mqttPort, onValueChange = { mqttPort = it },
                                    label = stringResource(R.string.settings_label_port), isNumber = true, modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // Mode 0: AP Hotspot Settings
                    if (connMode == 0) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = stringResource(R.string.settings_wifi_ap_hotspot_settings),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            CompactTextField(
                                value = apSSID, onValueChange = { apSSID = it },
                                label = stringResource(R.string.settings_label_ap_ssid)
                            )
                            CompactTextField(
                                value = apPass, onValueChange = { apPass = it },
                                label = stringResource(R.string.settings_label_ap_password), isPassword = true
                            )
                        }
                    }

                    // Mode 2: Debug Network Settings
                    if (connMode == 2) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = stringResource(R.string.settings_wifi_debug_settings),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            CompactTextField(
                                value = debugSSID, onValueChange = { debugSSID = it },
                                label = stringResource(R.string.settings_label_debug_ssid)
                            )
                            CompactTextField(
                                value = debugPass, onValueChange = { debugPass = it },
                                label = stringResource(R.string.settings_label_debug_password), isPassword = true
                            )
                        }
                    }

                    // Expandable Section for Other Network Modes
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showOtherNetworkModes = !showOtherNetworkModes }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_other_network_modes),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
                                IconButton(
                                    onClick = { showOtherNetworkModes = !showOtherNetworkModes },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = if (showOtherNetworkModes) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                        contentDescription = stringResource(R.string.settings_toggle_other_network)
                                    )
                                }
                            }

                            AnimatedVisibility(
                                visible = showOtherNetworkModes,
                                enter = expandVertically(),
                                exit = shrinkVertically()
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (connMode != 1) {
                                        Text(stringResource(R.string.settings_wifi_sta_mqtt), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                        CompactTextField(value = wifiSSID, onValueChange = { wifiSSID = it }, label = stringResource(R.string.settings_label_wifi_ssid))
                                        CompactTextField(value = wifiPass, onValueChange = { wifiPass = it }, label = stringResource(R.string.settings_label_wifi_password), isPassword = true)

                                        CompactTextField(value = mqttServer, onValueChange = { mqttServer = it }, label = stringResource(R.string.settings_label_mqtt_host))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            CompactTextField(value = mqttUser, onValueChange = { mqttUser = it }, label = stringResource(R.string.settings_label_username), modifier = Modifier.weight(1f))
                                            CompactTextField(value = mqttPass, onValueChange = { mqttPass = it }, label = stringResource(R.string.settings_label_password), isPassword = true, modifier = Modifier.weight(1f))
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            CompactTextField(value = mqttTopic, onValueChange = { mqttTopic = it }, label = stringResource(R.string.settings_label_topic), modifier = Modifier.weight(2f))
                                            CompactTextField(value = mqttPort, onValueChange = { mqttPort = it }, label = stringResource(R.string.settings_label_port), isNumber = true, modifier = Modifier.weight(1f))
                                        }
                                    }

                                    if (connMode != 0) {
                                        Text(stringResource(R.string.settings_wifi_ap_hotspot_settings), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                        CompactTextField(value = apSSID, onValueChange = { apSSID = it }, label = stringResource(R.string.settings_label_ap_ssid))
                                        CompactTextField(value = apPass, onValueChange = { apPass = it }, label = stringResource(R.string.settings_label_ap_password), isPassword = true)
                                    }

                                    if (connMode != 2) {
                                        Text(stringResource(R.string.settings_wifi_debug_settings), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                        CompactTextField(value = debugSSID, onValueChange = { debugSSID = it }, label = stringResource(R.string.settings_label_debug_ssid))
                                        CompactTextField(value = debugPass, onValueChange = { debugPass = it }, label = stringResource(R.string.settings_label_debug_password), isPassword = true)
                                    }
                                }
                            }
                        }
                    }

                    // Save Network Settings button
                    Button(
                        onClick = {
                            if (!isWifiOpen && wifiPass.isNotBlank() && wifiPass != "********" && wifiPass.length !in 8..64) {
                                viewModel.showMessage(context.getString(R.string.settings_wifi_pass_length))
                                return@Button
                            }
                            if (apPass.isNotBlank() && apPass != "********" && apPass.length !in 8..64) {
                                viewModel.showMessage(context.getString(R.string.settings_ap_pass_length))
                                return@Button
                            }
                            if (debugPass.isNotBlank() && debugPass != "********" && debugPass.length !in 8..64) {
                                viewModel.showMessage(context.getString(R.string.settings_debug_pass_length))
                                return@Button
                            }

                            val updates = mutableMapOf<String, Any>()
                            config?.let { c ->
                                if (connMode != c.connMode) updates["connMode"] = connMode
                                if (wifiSSID.isNotBlank() && wifiSSID != c.wifiSSID) updates["wifiSSID"] = wifiSSID
                                if (!isWifiOpen && wifiPass.isNotBlank() && wifiPass != "********" && wifiPass != c.wifiPass) {
                                    updates["wifiPass"] = wifiPass
                                } else if (isWifiOpen && c.wifiPass.isNotEmpty()) {
                                    updates["wifiPass"] = ""
                                }

                                if (apSSID.isNotBlank() && apSSID != c.apSSID) updates["apSSID"] = apSSID
                                if (apPass.isNotBlank() && apPass != "********" && apPass != c.apPass) updates["apPass"] = apPass

                                if (debugSSID.isNotBlank() && debugSSID != c.debugSSID) updates["debugSSID"] = debugSSID
                                if (debugPass.isNotBlank() && debugPass != "********" && debugPass != c.debugPass) updates["debugPass"] = debugPass

                                if (mqttServer.isNotBlank() && mqttServer != c.mqttServer) updates["mqttServer"] = mqttServer
                                mqttPort.toIntOrNull()?.let { if (it != c.mqttPort) updates["mqttPort"] = it }
                                if (mqttUser.isNotBlank() && mqttUser != c.mqttUser) updates["mqttUser"] = mqttUser
                                if (mqttPass.isNotBlank() && mqttPass != "********" && mqttPass != c.mqttPass) updates["mqttPass"] = mqttPass
                                if (mqttTopic.isNotBlank() && mqttTopic != c.mqttTopic) updates["mqttTopic"] = mqttTopic
                            }

                            var localConnChanged = false
                            if (mqttServer.isNotBlank() && mqttServer != PumpRepositoryProvider.getMqttHost()) localConnChanged = true
                            mqttPort.toIntOrNull()?.let { if (it != PumpRepositoryProvider.getMqttPort()) localConnChanged = true }
                            if (mqttUser.isNotBlank() && mqttUser != PumpRepositoryProvider.getMqttUser()) localConnChanged = true
                            if (mqttPass.isNotBlank() && mqttPass != "********" && mqttPass != PumpRepositoryProvider.getMqttPass()) localConnChanged = true
                            if (mqttTopic.isNotBlank() && mqttTopic != PumpRepositoryProvider.getMqttTopic()) localConnChanged = true

                            if (updates.isEmpty() && !localConnChanged) {
                                viewModel.saveConfig(emptyMap())
                            } else {
                                pendingNetworkUpdates = updates
                                pendingLocalConnectionChanged = localConnChanged
                                showNetworkConfirmDialog = true
                            }
                        },
                        enabled = !isSaving && config != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp),
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Save,
                            contentDescription = null,
                            modifier = Modifier
                                .size(18.dp)
                                .padding(end = 4.dp)
                        )
                        Text(stringResource(R.string.settings_save_network), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            // Protection Thresholds (mA)
            SectionHeader(title = stringResource(R.string.settings_protection_thresholds))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactTextField(
                    value = threshOff, onValueChange = { threshOff = it },
                    label = stringResource(R.string.settings_label_off), isNumber = true, modifier = Modifier.weight(1f),
                    onFocusLost = {
                        val newInt = threshOff.toIntOrNull()
                        if (newInt != null && newInt != config?.threshOff) {
                            val offVal = newInt
                            val dryVal = threshDry.toIntOrNull() ?: (config?.threshNoWater ?: 2000)
                            val runningVal = threshRunning.toIntOrNull() ?: (config?.threshRunning ?: 5000)
                            val overloadVal = threshOverload.toIntOrNull() ?: (config?.threshOverload ?: 20000)

                            if (!(offVal < dryVal && dryVal < runningVal && runningVal < overloadVal)) {
                                viewModel.showMessage(context.getString(R.string.settings_threshold_order))
                                threshOff = config?.threshOff?.toString() ?: "100"
                            } else {
                                pendingSingleField = PendingFieldChange(
                                    key = "threshOff",
                                    displayName = context.getString(R.string.settings_display_off_threshold),
                                    newValue = newInt,
                                    revertAction = { threshOff = config?.threshOff?.toString() ?: "100" }
                                )
                            }
                        }
                    }
                )
                CompactTextField(
                    value = threshDry, onValueChange = { threshDry = it },
                    label = stringResource(R.string.settings_label_dry_run), isNumber = true, modifier = Modifier.weight(1f),
                    onFocusLost = {
                        val newInt = threshDry.toIntOrNull()
                        if (newInt != null && newInt != config?.threshNoWater) {
                            val offVal = threshOff.toIntOrNull() ?: (config?.threshOff ?: 100)
                            val dryVal = newInt
                            val runningVal = threshRunning.toIntOrNull() ?: (config?.threshRunning ?: 5000)
                            val overloadVal = threshOverload.toIntOrNull() ?: (config?.threshOverload ?: 20000)

                            if (!(offVal < dryVal && dryVal < runningVal && runningVal < overloadVal)) {
                                viewModel.showMessage(context.getString(R.string.settings_threshold_order))
                                threshDry = config?.threshNoWater?.toString() ?: "2000"
                            } else {
                                pendingSingleField = PendingFieldChange(
                                    key = "threshNoWater",
                                    displayName = context.getString(R.string.settings_display_dry_threshold),
                                    newValue = newInt,
                                    revertAction = { threshDry = config?.threshNoWater?.toString() ?: "2000" }
                                )
                            }
                        }
                    }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactTextField(
                    value = threshRunning, onValueChange = { threshRunning = it },
                    label = stringResource(R.string.settings_label_running), isNumber = true, modifier = Modifier.weight(1f),
                    onFocusLost = {
                        val newInt = threshRunning.toIntOrNull()
                        if (newInt != null && newInt != config?.threshRunning) {
                            val offVal = threshOff.toIntOrNull() ?: (config?.threshOff ?: 100)
                            val dryVal = threshDry.toIntOrNull() ?: (config?.threshNoWater ?: 2000)
                            val runningVal = newInt
                            val overloadVal = threshOverload.toIntOrNull() ?: (config?.threshOverload ?: 20000)

                            if (!(offVal < dryVal && dryVal < runningVal && runningVal < overloadVal)) {
                                viewModel.showMessage(context.getString(R.string.settings_threshold_order))
                                threshRunning = config?.threshRunning?.toString() ?: "5000"
                            } else {
                                pendingSingleField = PendingFieldChange(
                                    key = "threshRunning",
                                    displayName = context.getString(R.string.settings_display_running_threshold),
                                    newValue = newInt,
                                    revertAction = { threshRunning = config?.threshRunning?.toString() ?: "5000" }
                                )
                            }
                        }
                    }
                )
                CompactTextField(
                    value = threshOverload, onValueChange = { threshOverload = it },
                    label = stringResource(R.string.settings_label_overload), isNumber = true, modifier = Modifier.weight(1f),
                    onFocusLost = {
                        val newInt = threshOverload.toIntOrNull()
                        if (newInt != null && newInt != config?.threshOverload) {
                            val offVal = threshOff.toIntOrNull() ?: (config?.threshOff ?: 100)
                            val dryVal = threshDry.toIntOrNull() ?: (config?.threshNoWater ?: 2000)
                            val runningVal = threshRunning.toIntOrNull() ?: (config?.threshRunning ?: 5000)
                            val overloadVal = newInt

                            if (!(offVal < dryVal && dryVal < runningVal && runningVal < overloadVal)) {
                                viewModel.showMessage(context.getString(R.string.settings_threshold_order))
                                threshOverload = config?.threshOverload?.toString() ?: "20000"
                            } else {
                                pendingSingleField = PendingFieldChange(
                                    key = "threshOverload",
                                    displayName = context.getString(R.string.settings_display_overload_threshold),
                                    newValue = newInt,
                                    revertAction = { threshOverload = config?.threshOverload?.toString() ?: "20000" }
                                )
                            }
                        }
                    }
                )
            }

            // Timeouts (ms)
            SectionHeader(title = stringResource(R.string.settings_timeouts))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactTextField(
                    value = dryTimeout, onValueChange = { dryTimeout = it },
                    label = stringResource(R.string.settings_label_dry_run_timeout), isNumber = true, modifier = Modifier.weight(1f),
                    onFocusLost = {
                        val newInt = dryTimeout.toIntOrNull()
                        if (newInt != null && newInt != config?.dryTimeout) {
                            if (newInt <= 0) {
                                viewModel.showMessage(context.getString(R.string.settings_timeout_positive))
                                dryTimeout = config?.dryTimeout?.toString() ?: "7000"
                            } else {
                                pendingSingleField = PendingFieldChange(
                                    key = "dryTimeout",
                                    displayName = context.getString(R.string.settings_display_dry_timeout),
                                    newValue = newInt,
                                    revertAction = { dryTimeout = config?.dryTimeout?.toString() ?: "7000" }
                                )
                            }
                        }
                    }
                )
                CompactTextField(
                    value = overloadTimeout, onValueChange = { overloadTimeout = it },
                    label = stringResource(R.string.settings_label_overload_timeout), isNumber = true, modifier = Modifier.weight(1f),
                    onFocusLost = {
                        val newInt = overloadTimeout.toIntOrNull()
                        if (newInt != null && newInt != config?.overloadTimeout) {
                            if (newInt <= 0) {
                                viewModel.showMessage(context.getString(R.string.settings_timeout_positive))
                                overloadTimeout = config?.overloadTimeout?.toString() ?: "1000"
                            } else {
                                pendingSingleField = PendingFieldChange(
                                    key = "overloadTimeout",
                                    displayName = context.getString(R.string.settings_display_overload_timeout),
                                    newValue = newInt,
                                    revertAction = { overloadTimeout = config?.overloadTimeout?.toString() ?: "1000" }
                                )
                            }
                        }
                    }
                )
            }

            // Relay Startup Mode
            SectionHeader(title = stringResource(R.string.settings_relay_startup_mode))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    stringResource(R.string.settings_relay_off) to 0,
                    stringResource(R.string.settings_relay_on) to 1,
                    stringResource(R.string.settings_relay_keep_last) to 2
                ).forEach { (label, modeIndex) ->
                    val selected = relayStartMode == modeIndex
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(MaterialTheme.shapes.small)
                            .clickable {
                                if (modeIndex != relayStartMode) {
                                    showRelayModeDialog = modeIndex
                                }
                            },
                        shape = MaterialTheme.shapes.small,
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Calibration Coefficients
            SectionHeader(title = stringResource(R.string.settings_calibration_coefficients))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactTextField(
                    value = cCal, onValueChange = { cCal = it },
                    label = stringResource(R.string.settings_label_current_cal), isNumber = true, modifier = Modifier.weight(1f),
                    onFocusLost = {
                        val newDbl = cCal.toDoubleOrNull()
                        if (newDbl != null && newDbl != config?.cCal) {
                            pendingSingleField = PendingFieldChange(
                                key = "cCal",
                                displayName = context.getString(R.string.settings_display_cal_current),
                                newValue = newDbl,
                                revertAction = { cCal = config?.cCal?.toString() ?: "1.0" }
                            )
                        }
                    }
                )
                CompactTextField(
                    value = vCal, onValueChange = { vCal = it },
                    label = stringResource(R.string.settings_label_voltage_cal), isNumber = true, modifier = Modifier.weight(1f),
                    onFocusLost = {
                        val newDbl = vCal.toDoubleOrNull()
                        if (newDbl != null && newDbl != config?.vCal) {
                            pendingSingleField = PendingFieldChange(
                                key = "vCal",
                                displayName = context.getString(R.string.settings_display_cal_voltage),
                                newValue = newDbl,
                                revertAction = { vCal = config?.vCal?.toString() ?: "1.0" }
                            )
                        }
                    }
                )
                CompactTextField(
                    value = pCal, onValueChange = { pCal = it },
                    label = stringResource(R.string.settings_label_power_cal), isNumber = true, modifier = Modifier.weight(1f),
                    onFocusLost = {
                        val newDbl = pCal.toDoubleOrNull()
                        if (newDbl != null && newDbl != config?.pCal) {
                            pendingSingleField = PendingFieldChange(
                                key = "pCal",
                                displayName = context.getString(R.string.settings_display_cal_power),
                                newValue = newDbl,
                                revertAction = { pCal = config?.pCal?.toString() ?: "1.0" }
                            )
                        }
                    }
                )
            }

            // Auto-Calibration Card (Real-World Measured Values)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showCalibCard = !showCalibCard }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.settings_auto_calibrate),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        IconButton(
                            onClick = { showCalibCard = !showCalibCard },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = if (showCalibCard) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = stringResource(R.string.settings_toggle_calibrate)
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = showCalibCard,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CompactTextField(
                                    value = realAmps,
                                    onValueChange = { realAmps = it },
                                    label = stringResource(R.string.settings_label_current),
                                    isNumber = true,
                                    modifier = Modifier.weight(1f)
                                )
                                CompactTextField(
                                    value = realVolts,
                                    onValueChange = { realVolts = it },
                                    label = stringResource(R.string.settings_label_voltage),
                                    isNumber = true,
                                    modifier = Modifier.weight(1f)
                                )
                                CompactTextField(
                                    value = realWatts,
                                    onValueChange = { realWatts = it },
                                    label = stringResource(R.string.settings_label_power),
                                    isNumber = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = { showResetCalibConfirmDialog = true },
                                    enabled = !isSaving && config != null,
                                    modifier = Modifier.height(34.dp),
                                    shape = MaterialTheme.shapes.small,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.RestartAlt,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.settings_reset),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }

                                Button(
                                    onClick = {
                                        val payload = mutableMapOf<String, Any>()
                                        realAmps.toDoubleOrNull()?.let { payload["current"] = it }
                                        realVolts.toDoubleOrNull()?.let { payload["voltage"] = it }
                                        realWatts.toDoubleOrNull()?.let { payload["power"] = it }

                                        if (payload.isEmpty()) {
                                            viewModel.showMessage(context.getString(R.string.settings_enter_real_values))
                                        } else {
                                            showCalibConfirmDialog = payload
                                        }
                                    },
                                    enabled = !isSaving && config != null,
                                    modifier = Modifier.height(34.dp),
                                    shape = MaterialTheme.shapes.small,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.settings_send_calibration),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // System Log Settings
            SectionHeader(title = stringResource(R.string.settings_system_logging))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_enable_system_log),
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = sysLogFileEnabled,
                    onCheckedChange = { targetState ->
                        if (targetState != sysLogFileEnabled) {
                            showLogSwitchDialog = targetState
                        }
                    }
                )
            }
            CompactTextField(
                value = sysLogFileLevel, onValueChange = { sysLogFileLevel = it },
                label = stringResource(R.string.settings_label_log_level), isNumber = true,
                onFocusLost = {
                    val newInt = sysLogFileLevel.toIntOrNull()
                    if (newInt != null && newInt != config?.sysLogFileLevel) {
                        pendingSingleField = PendingFieldChange(
                            key = "sysLogFileLevel",
                            displayName = context.getString(R.string.settings_display_log_level),
                            newValue = newInt,
                            revertAction = { sysLogFileLevel = config?.sysLogFileLevel?.toString() ?: "0" }
                        )
                    }
                }
            )

            // Danger Zone
            SectionHeader(title = stringResource(R.string.settings_danger_zone))
            OutlinedButton(
                onClick = { showManualRebootDialog = true },
                modifier = Modifier.fillMaxWidth().height(40.dp),
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Filled.RestartAlt, null, modifier = Modifier.padding(end = 4.dp))
                Text(stringResource(R.string.settings_reboot), style = MaterialTheme.typography.labelMedium)
            }

            Spacer(modifier = Modifier.height(80.dp))
        }

        // Initial Loading Spinner Overlay
        if (config == null) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background.copy(alpha = 0.95f)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.settings_fetching_config),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.settings_retrying),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
                            text = stringResource(R.string.settings_saving),
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
                            text = stringResource(R.string.settings_rebooting),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
    }

    // Wi-Fi Networks Scanned Result Dialog
    if (showWifiScanDialog) {
        Dialog(onDismissRequest = { viewModel.dismissWifiScanDialog() }) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.settings_available_wifi),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick = { viewModel.scanWifi() },
                            enabled = !isScanningWifi
                        ) {
                            if (isScanningWifi) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.settings_rescan))
                            }
                        }
                    }

                    if (wifiNetworks.isEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_no_wifi),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val scanScrollState = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .drawVerticalScrollbar(
                                    scrollState = scanScrollState,
                                    color = MaterialTheme.colorScheme.primary,
                                    width = 5.dp
                                )
                                .verticalScroll(scanScrollState)
                                .padding(end = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            wifiNetworks.sortedByDescending { it.rssi }.forEach { net ->
                                val isOpen = !net.isEncrypt

                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.small)
                                        .clickable {
                                            if (net.name.isNotBlank()) {
                                                wifiSSID = net.name
                                                isWifiOpen = isOpen
                                                wifiPass = ""
                                            }
                                            viewModel.dismissWifiScanDialog()
                                        },
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = net.name.ifBlank { context.getString(R.string.settings_hidden_network) },
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                if (net.isEncrypt) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Lock,
                                                        contentDescription = stringResource(R.string.settings_secured_network),
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                        modifier = Modifier.size(14.dp).offset(y = (-1).dp)
                                                    )
                                                }
                                            }
                                            if (net.bssid.isNotBlank()) {
                                                Text(
                                                    text = net.bssid,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                                )
                                            }
                                        }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            WifiSignalBars(rssi = net.rssi)
                                            Text(
                                                text = "${net.rssi} dBm",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.dismissWifiScanDialog() },
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(stringResource(R.string.settings_close))
                        }
                    }
                }
            }
        }
    }

    // Single Field Change Confirmation Dialog
    pendingSingleField?.let { change ->
        ConfirmDialog(
            title = stringResource(R.string.settings_confirm_save_title, change.displayName),
            message = stringResource(R.string.settings_confirm_save_message, change.displayName, change.newValue.toString()),
            confirmText = stringResource(R.string.settings_confirm_save),
            onConfirm = {
                val fieldToSave = change
                pendingSingleField = null
                viewModel.saveConfig(mapOf(fieldToSave.key to fieldToSave.newValue))
            },
            onDismiss = {
                change.revertAction()
                pendingSingleField = null
            }
        )
    }

    // Save Network Settings Confirmation Dialog
    if (showNetworkConfirmDialog) {
        ConfirmDialog(
            title = stringResource(R.string.settings_confirm_network_title),
            message = if (pendingNetworkUpdates.isNotEmpty()) 
                stringResource(R.string.settings_network_modified, pendingNetworkUpdates.size, pendingNetworkUpdates.keys.joinToString(", "))
            else 
                stringResource(R.string.settings_save_local_mqtt),
            confirmText = stringResource(R.string.settings_confirm_save),
            onConfirm = {
                showNetworkConfirmDialog = false
                if (pendingLocalConnectionChanged) {
                    val targetHost = mqttServer.takeIf { it.isNotBlank() } ?: PumpRepositoryProvider.getMqttHost()
                    val dynamicWsUrl = if (targetHost.startsWith("192.168.") || targetHost.startsWith("10.") || targetHost.startsWith("172.")) "ws://$targetHost:82" else BuildConfig.WEBSOCKET_URL
                    PumpRepositoryProvider.saveMqttConfig(
                        host = targetHost,
                        port = mqttPort.toIntOrNull() ?: PumpRepositoryProvider.getMqttPort(),
                        user = mqttUser.takeIf { it.isNotBlank() } ?: PumpRepositoryProvider.getMqttUser(),
                        pass = mqttPass.takeIf { it.isNotBlank() } ?: PumpRepositoryProvider.getMqttPass(),
                        topic = mqttTopic.takeIf { it.isNotBlank() } ?: PumpRepositoryProvider.getMqttTopic(),
                        wsUrl = dynamicWsUrl
                    )
                }
                if (pendingNetworkUpdates.isNotEmpty()) {
                    viewModel.saveConfig(pendingNetworkUpdates)
                }
            },
            onDismiss = { showNetworkConfirmDialog = false }
        )
    }

    // Mode Switch Dialog (PUMP MODE / SWITCH MODE)
    showModeDialog?.let { targetMode ->
        ConfirmDialog(
            title = if (targetMode) stringResource(R.string.settings_switch_pump_mode) else stringResource(R.string.settings_switch_switch_mode),
            message = if (targetMode)
                stringResource(R.string.settings_pump_mode_desc)
            else
                stringResource(R.string.settings_switch_mode_desc),
            confirmText = stringResource(R.string.settings_confirm_and_save),
            isDangerous = !targetMode,
            onConfirm = {
                showModeDialog = null
                pumpMode = targetMode
                viewModel.saveConfig(mapOf("pumpMode" to targetMode))
            },
            onDismiss = { showModeDialog = null }
        )
    }

    // Relay Startup Mode Dialog
    showRelayModeDialog?.let { targetMode ->
        val modeText = when (targetMode) {
            0 -> stringResource(R.string.settings_relay_off)
            1 -> stringResource(R.string.settings_relay_on)
            else -> stringResource(R.string.settings_relay_keep_last)
        }
        ConfirmDialog(
            title = stringResource(R.string.settings_change_relay_mode),
            message = stringResource(R.string.settings_relay_mode_message, modeText),
            confirmText = stringResource(R.string.settings_confirm_and_save),
            onConfirm = {
                showRelayModeDialog = null
                relayStartMode = targetMode
                viewModel.saveConfig(mapOf("relayStartMode" to targetMode))
            },
            onDismiss = { showRelayModeDialog = null }
        )
    }

    // System Log Switch Dialog
    showLogSwitchDialog?.let { targetState ->
        ConfirmDialog(
            title = if (targetState) stringResource(R.string.settings_enable_log_title) else stringResource(R.string.settings_disable_log_title),
            message = if (targetState) stringResource(R.string.settings_enable_log_msg) else stringResource(R.string.settings_disable_log_msg),
            confirmText = stringResource(R.string.settings_confirm_and_save),
            onConfirm = {
                showLogSwitchDialog = null
                sysLogFileEnabled = targetState
                viewModel.saveConfig(mapOf("sysLogFileEnabled" to targetState))
            },
            onDismiss = { showLogSwitchDialog = null }
        )
    }

    // Reboot Required Dialog
    if (showRebootPrompt) {
        ConfirmDialog(
            title = stringResource(R.string.settings_reboot_required),
            message = stringResource(R.string.settings_reboot_required_msg),
            confirmText = stringResource(R.string.settings_reboot_now),
            dismissText = stringResource(R.string.settings_later),
            isDangerous = false,
            onConfirm = {
                viewModel.reboot()
            },
            onDismiss = {
                viewModel.dismissRebootPrompt()
            }
        )
    }

    // Manual Reboot Dialog
    if (showManualRebootDialog) {
        ConfirmDialog(
            title = stringResource(R.string.settings_reboot_device),
            message = stringResource(R.string.settings_reboot_device_msg),
            confirmText = stringResource(R.string.settings_reboot),
            isDangerous = true,
            onConfirm = {
                showManualRebootDialog = false
                viewModel.reboot()
            },
            onDismiss = { showManualRebootDialog = false }
        )
    }

    // Factory Reset Dialog
    if (showResetDialog) {
        ConfirmDialog(
            title = stringResource(R.string.settings_factory_reset),
            message = stringResource(R.string.settings_factory_reset_msg),
            confirmText = stringResource(R.string.settings_reset),
            isDangerous = true,
            onConfirm = {
                showResetDialog = false
                viewModel.factoryReset()
            },
            onDismiss = { showResetDialog = false }
        )
    }

    // Auto-Calibration Confirmation Dialog
    showCalibConfirmDialog?.let { payload ->
        val details = payload.entries.joinToString("\n") { (k, v) ->
            val name = when(k) {
                "current" -> context.getString(R.string.settings_label_current)
                "voltage" -> context.getString(R.string.settings_label_voltage)
                "power" -> context.getString(R.string.settings_label_power)
                else -> k
            }
            "• $name: $v"
        }
        ConfirmDialog(
            title = stringResource(R.string.settings_confirm_calibrate_title),
            message = stringResource(R.string.settings_confirm_calibrate_msg, details),
            confirmText = stringResource(R.string.settings_send_calibration),
            onConfirm = {
                showCalibConfirmDialog = null
                viewModel.calibrate(payload)
            },
            onDismiss = { showCalibConfirmDialog = null }
        )
    }

    // Reset Calibration Confirmation Dialog
    if (showResetCalibConfirmDialog) {
        ConfirmDialog(
            title = stringResource(R.string.settings_reset_calibration_title),
            message = stringResource(R.string.settings_reset_calibration_msg),
            confirmText = stringResource(R.string.settings_reset_to_default),
            isDangerous = true,
            onConfirm = {
                showResetCalibConfirmDialog = false
                viewModel.resetCalibration()
            },
            onDismiss = { showResetCalibConfirmDialog = false }
        )
    }
}
