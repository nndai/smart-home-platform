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
import com.nndai.myhome.data.pairing.PairingDevice
import com.nndai.myhome.data.pairing.PairingState
import com.nndai.myhome.data.pairing.WifiNetworkInfo

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
                title = { Text("Add Device", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                        message = "Đang mở trình tìm kiếm thiết bị...",
                        detail = "Chấp nhận pop-up hệ thống Android hiển thị trên màn hình để kết nối"
                    )
                }

                is PairingState.ScanningDevices -> {
                    LaunchedEffect(Unit) {
                        viewModel.connectSystemChooser()
                    }
                    ProgressContent(
                        message = "Đang tìm kiếm thiết bị MyHome...",
                        detail = "Chấp nhận thông báo/pop-up hệ thống để kết nối thiết bị"
                    )
                }

                is PairingState.ConnectingAp -> ProgressContent(
                    message = "Đang kết nối thiết bị MyHome...",
                    detail = "Hãy nhấn 'Kết nối / Allow' trên pop-up của hệ thống Android"
                )

                is PairingState.DeviceReady -> LaunchedEffect(Unit) { viewModel.scanWifiOnDevice() }
                    .let { ProgressContent(message = "Đang kết nối...", detail = "Đọc cấu hình thiết bị") }

                is PairingState.ScanningWifi -> ProgressContent(
                    message = "Đang quét WiFi lân cận...",
                    detail = "Thiết bị đang quét các mạng xung quanh"
                )

                is PairingState.WaitingForReconnect -> ProgressContent(
                    message = "Đang chờ thiết bị quét xong...",
                    detail = "Thiết bị đã tạm ngắt WiFi để quét, kết nối sẽ tự phục hồi trong vài giây"
                )

                is PairingState.WifiList -> WifiSelectContent(
                    networks = current.networks,
                    onRescan = { viewModel.scanWifiOnDevice() },
                    onConnect = { ssid, pass -> viewModel.pair(ssid, pass) }
                )

                is PairingState.SendingPair -> ProgressContent(
                    message = "Đang cấu hình thiết bị...",
                    detail = "Thiết bị sẽ khởi động lại sau khi lưu cấu hình"
                )

                is PairingState.Claiming -> ProgressContent(
                    message = "Đang lưu thiết bị vào tài khoản...",
                    detail = "Chờ điện thoại kết nối lại WiFi nhà"
                )

                is PairingState.Claimed -> SuccessContent(
                    deviceId = current.deviceId,
                    name = modelName(current.profile),
                    onDone = {
                        onPairComplete(current.deviceId, current.profile, modelName(current.profile), current.controlKeyHex)
                    }
                )

                is PairingState.ClaimSavedOffline -> SuccessContent(
                    deviceId = current.deviceId,
                    name = modelName(current.profile),
                    offline = true,
                    onDone = {
                        onPairComplete(current.deviceId, current.profile, modelName(current.profile), current.controlKeyHex)
                    }
                )

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
            text = "Quét thiết bị xung quanh",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            text = if (permissionGranted)
                "App sẽ tìm các thiết bị myhome trong mạng WiFi gần bạn"
            else
                "App cần quyền quét WiFi để tìm thiết bị",
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
            Text(if (permissionGranted) "Quét danh sách WiFi" else "Cấp quyền & quét", fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = onSystemChooserClick,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(48.dp),
            shape = MaterialTheme.shapes.small
        ) {
            Text("Tìm & Kết nối qua System Popup", fontWeight = FontWeight.Bold)
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
                Text("Thiết bị tìm thấy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${devices.size} thiết bị myhome",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
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
                    text = if (scanInProgress) "Đang quét..." else "Không tìm thấy thiết bị trong danh sách",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Điện thoại có thể bị khóa kênh Wi-Fi nhà. Hãy bấm nút bên dưới để mở System Popup quét toàn bộ 14 kênh sóng:",
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
                    Text("Tìm & Kết nối bằng System Popup 🚀", fontWeight = FontWeight.Bold)
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
                Text("Chọn WiFi nhà", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Thiết bị quét được ${networks.size} mạng — chọn mạng nhà bạn",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRescan) {
                Icon(Icons.Filled.Refresh, contentDescription = "Quét lại")
            }
        }

        if (networks.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Thiết bị không quét được mạng nào — hãy thử lại",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                OutlinedButton(onClick = onRescan) {
                    Text("Quét lại")
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
                                            contentDescription = "Encrypted",
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
                label = { Text("Mật khẩu WiFi") },
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
                text = if (connecting) "Đang kết nối..." else "Connect",
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun WifiSignalBars(
    rssi: Int,
    modifier: Modifier = Modifier
) {
    val activeLevel = when {
        rssi >= -55 -> 4
        rssi >= -67 -> 3
        rssi >= -78 -> 2
        else -> 1
    }

    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)

    androidx.compose.foundation.Canvas(
        modifier = modifier.size(20.dp)
    ) {
        val strokeWidth = 2.dp.toPx()
        val centerX = size.width / 2f
        val centerY = size.height - strokeWidth / 2f

        val dotRadius = strokeWidth * 0.9f
        drawCircle(
            color = if (activeLevel >= 1) activeColor else inactiveColor,
            radius = dotRadius,
            center = androidx.compose.ui.geometry.Offset(centerX, centerY - dotRadius)
        )

        val radii = listOf(
            strokeWidth * 2.6f,
            strokeWidth * 4.4f,
            strokeWidth * 6.2f
        )

        for (i in 0 until 3) {
            val level = i + 2
            val r = radii[i]
            val color = if (activeLevel >= level) activeColor else inactiveColor

            drawArc(
                color = color,
                startAngle = 225f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(centerX - r, (centerY - dotRadius) - r),
                size = androidx.compose.ui.geometry.Size(r * 2f, r * 2f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = strokeWidth,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
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
            text = if (offline) "Ghép nối xong, chưa lưu vào tài khoản" else "Ghép nối thành công",
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
                "Thiết bị đang khởi động lại và kết nối WiFi nhà bạn. Chưa gửi được thông tin lên tài khoản vì mất mạng — sẽ tự động đồng bộ khi có mạng trở lại."
            } else {
                "Thiết bị đang khởi động lại và kết nối WiFi nhà bạn. Bạn sẽ thấy nó trong danh sách thiết bị."
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
            Text("Xong", fontWeight = FontWeight.Bold)
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
        Text("Không thành công", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
            Text("Thử lại", fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(48.dp),
            shape = MaterialTheme.shapes.small
        ) {
            Text("Quay lại")
        }
    }
}

private fun modelName(model: String): String = when (model.lowercase()) {
    "pump" -> "Máy bơm"
    "switch" -> "Công tắc"
    "fan" -> "Quạt"
    "lamp" -> "Đèn"
    else -> model
}

private fun modelIcon(model: String): ImageVector = when (model.lowercase()) {
    "pump" -> Icons.Filled.WaterDrop
    "switch", "lamp" -> Icons.Filled.Lightbulb
    "fan" -> Icons.Filled.ModeFanOff
    else -> Icons.Filled.DeviceUnknown
}
