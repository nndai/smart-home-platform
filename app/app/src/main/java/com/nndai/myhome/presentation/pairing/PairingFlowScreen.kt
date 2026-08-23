package com.nndai.myhome.presentation.pairing

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeviceUnknown
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ModeFanOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiLock
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import com.nndai.myhome.R
import com.nndai.myhome.data.pairing.PairingDevice
import com.nndai.myhome.data.pairing.PairingState
import com.nndai.myhome.data.pairing.WifiNetworkInfo
import com.nndai.myhome.presentation.device.components.WifiSignalBars

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingFlowScreen(
    onNavigateBack: () -> Unit,
    onPairComplete: (deviceId: String, profile: String, name: String, controlKeyHex: String) -> Unit,
    viewModel: PairingViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val scanDevices by viewModel.scanDevices.collectAsState()
    val scanInProgress by viewModel.scanInProgress.collectAsState()
    val context = LocalContext.current

    val locationPermission = Manifest.permission.ACCESS_FINE_LOCATION
    val nearbyPermission = Manifest.permission.NEARBY_WIFI_DEVICES
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) nearbyPermission else locationPermission
    val permissionGranted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    var permissionRequested by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionRequested = true
        if (granted) viewModel.startScan()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_device), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp)
        ) {
            when (val current = state) {
                is PairingState.Idle -> {
                    LaunchedEffect(permissionGranted) {
                        if (permissionGranted) {
                            viewModel.connectSystemChooser()
                        } else {
                            permissionLauncher.launch(permission)
                        }
                    }
                    ProgressContent(
                        message = stringResource(R.string.pair_scanning_ble),
                        detail = stringResource(R.string.pair_scanning_ble_detail)
                    )
                }

                is PairingState.ScanningDevices -> {
                    LaunchedEffect(Unit) {
                        viewModel.connectSystemChooser()
                    }
                    ProgressContent(
                        message = stringResource(R.string.pair_finding_devices),
                        detail = stringResource(R.string.pair_finding_detail)
                    )
                }

                is PairingState.ConnectingAp -> ProgressContent(
                    message = stringResource(R.string.pair_connecting_device),
                    detail = stringResource(R.string.pair_connecting_detail)
                )

                is PairingState.DeviceReady -> LaunchedEffect(Unit) { viewModel.scanWifiOnDevice() }
                    .let { ProgressContent(message = stringResource(R.string.pair_reading_config), detail = stringResource(R.string.pair_reading_config_detail)) }

                is PairingState.ScanningWifi -> ProgressContent(
                    message = stringResource(R.string.pair_scan_wifi),
                    detail = stringResource(R.string.pair_scan_wifi_detail)
                )

                is PairingState.WaitingForReconnect -> ProgressContent(
                    message = stringResource(R.string.pair_wait_wifi),
                    detail = stringResource(R.string.pair_wait_wifi_detail)
                )

                is PairingState.WifiList -> WifiSelectContent(
                    networks = current.networks,
                    onRescan = { viewModel.scanWifiOnDevice() },
                    onConnect = { ssid, pass -> viewModel.pair(ssid, pass) }
                )

                is PairingState.SendingPair -> ProgressContent(
                    message = stringResource(R.string.pair_configuring),
                    detail = stringResource(R.string.pair_configuring_detail)
                )

                is PairingState.Claiming -> ProgressContent(
                    message = stringResource(R.string.pair_saving_account),
                    detail = stringResource(R.string.pair_saving_detail)
                )

                is PairingState.Claimed -> {
                    val deviceModelName = modelName(current.profile)
                    SuccessContent(
                        deviceId = current.deviceId,
                        name = deviceModelName,
                        onDone = {
                            onPairComplete(current.deviceId, current.profile, deviceModelName, current.controlKeyHex)
                        }
                    )
                }

                is PairingState.ClaimSavedOffline -> {
                    val deviceModelName = modelName(current.profile)
                    SuccessContent(
                        deviceId = current.deviceId,
                        name = deviceModelName,
                        offline = true,
                        onDone = {
                            onPairComplete(current.deviceId, current.profile, deviceModelName, current.controlKeyHex)
                        }
                    )
                }

                is PairingState.ClaimFailed -> ErrorContent(
                    message = current.message,
                    onRetry = { viewModel.retryClaim() },
                    onBack = onNavigateBack
                )

                is PairingState.Failed -> ErrorContent(
                    message = current.message,
                    onRetry = { viewModel.retryFromFailure() },
                    onBack = onNavigateBack
                )
            }
        }
    }
}

@Composable
private fun IdleContent(
    permissionGranted: Boolean,
    onScanClick: () -> Unit,
    onSystemChooserClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Wifi,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Text(
            text = stringResource(R.string.pair_scan_nearby_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            text = if (permissionGranted)
                stringResource(R.string.pair_scan_nearby_desc)
            else
                stringResource(R.string.pair_permission_needed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onScanClick,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(48.dp),
            shape = MaterialTheme.shapes.small
        ) {
            Text(if (permissionGranted) stringResource(R.string.pair_scan_wifi_list) else stringResource(R.string.pair_grant_and_scan), fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = onSystemChooserClick,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(48.dp),
            shape = MaterialTheme.shapes.small
        ) {
            Text(stringResource(R.string.pair_system_popup_action), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DeviceListContent(
    devices: List<PairingDevice>,
    scanInProgress: Boolean,
    onRefresh: () -> Unit,
    onDeviceClick: (PairingDevice) -> Unit,
    onSystemChooserClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.pair_devices_found), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.pair_devices_count, devices.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.system_refresh))
            }
        }

        if (scanInProgress && devices.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (devices.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.DeviceUnknown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(56.dp)
                )
                Text(
                    text = if (scanInProgress) stringResource(R.string.pair_scanning_or_empty) else stringResource(R.string.pair_none_in_list),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.pair_channel_blocked_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onSystemChooserClick,
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(48.dp),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(stringResource(R.string.pair_popup_action), fontWeight = FontWeight.Bold)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices) { device ->
                    PairingDeviceRow(device = device, onClick = { onDeviceClick(device) })
                }
            }
        }
    }
}

@Composable
private fun PairingDeviceRow(device: PairingDevice, onClick: () -> Unit) {
    val icon = modelIcon(device.model)
    val tint = MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier
            .fillMaxWidth()
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
            Surface(
                shape = MaterialTheme.shapes.small,
                color = tint.copy(alpha = 0.12f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = modelName(device.model),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = device.ssid,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "${device.rssi} dBm",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProgressContent(message: String, detail: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator()
        Text(message, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

@Composable
private fun WifiSelectContent(
    networks: List<WifiNetworkInfo>,
    onRescan: () -> Unit,
    onConnect: (ssid: String, pass: String) -> Unit
) {
    var selected by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.pair_pick_home_wifi), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.pair_networks_found, networks.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRescan) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.pair_rescan_desc))
            }
        }

        if (networks.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(R.string.pair_no_networks),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                OutlinedButton(onClick = onRescan) {
                    Text(stringResource(R.string.pair_rescan))
                }
            }
        } else {
            val sortedNetworks = networks.sortedByDescending { it.rssi }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                items(sortedNetworks) { network ->
                    val isSelected = selected == network.name
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = network.name
                                password = ""
                            },
                        shape = MaterialTheme.shapes.medium,
                        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            WifiSignalBars(rssi = network.rssi)
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = network.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (network.encrypted) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            imageVector = Icons.Filled.Lock,
                                            contentDescription = stringResource(R.string.desc_encrypted),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp).offset(y = (-1).dp)
                                        )
                                    }
                                }
                                if (network.bssid.isNotBlank()) {
                                    Text(
                                        text = network.bssid.uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Text(
                                text = "${network.rssi} dBm",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        val selectedNetwork = networks.firstOrNull { it.name == selected }
        if (selectedNetwork != null && selectedNetwork.encrypted) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.pair_label_wifi_password)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.small
            )
        }

        Button(
            onClick = {
                if (selectedNetwork != null) {
                    connecting = true
                    onConnect(selectedNetwork.name, password)
                }
            },
            enabled = selectedNetwork != null && (!selectedNetwork.encrypted || password.isNotEmpty()),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = if (connecting) stringResource(R.string.pair_connecting) else stringResource(R.string.pair_connect),
                fontWeight = FontWeight.Bold
            )
        }
    }
}



@Composable
private fun SuccessContent(
    deviceId: String,
    name: String,
    offline: Boolean = false,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = if (offline) Icons.Outlined.Cloud else Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = if (offline) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp)
        )
        Text(
            text = if (offline) stringResource(R.string.pair_offline_saved) else stringResource(R.string.pair_success),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "$name (${deviceId.takeLast(6)})",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = if (offline) {
                stringResource(R.string.pair_offline_desc)
            } else {
                stringResource(R.string.pair_success_desc)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(48.dp),
            shape = MaterialTheme.shapes.small
        ) {
            Text(stringResource(R.string.action_done), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(64.dp)
        )
        Text(stringResource(R.string.pair_failed_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(48.dp),
            shape = MaterialTheme.shapes.small
        ) {
            Text(stringResource(R.string.pair_retry), fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(48.dp),
            shape = MaterialTheme.shapes.small
        ) {
            Text(stringResource(R.string.pair_go_back))
        }
    }
}

@Composable
private fun modelName(model: String): String = when (model.lowercase()) {
    "pump" -> stringResource(R.string.pair_profile_pump)
    "switch" -> stringResource(R.string.pair_profile_switch)
    "fan" -> stringResource(R.string.pair_profile_fan)
    "lamp" -> stringResource(R.string.pair_profile_lamp)
    else -> model
}

private fun modelIcon(model: String): ImageVector = when (model.lowercase()) {
    "pump" -> Icons.Filled.WaterDrop
    "switch", "lamp" -> Icons.Filled.Lightbulb
    "fan" -> Icons.Filled.ModeFanOff
    else -> Icons.Filled.DeviceUnknown
}
