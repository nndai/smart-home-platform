package com.nndai.myhome.presentation.device.management

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeviceUnknown
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.ModeFanOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch
import com.nndai.myhome.core.theme.CyanBlue
import com.nndai.myhome.core.theme.GreenOk
import com.nndai.myhome.core.theme.OrangeWarning
import com.nndai.myhome.core.theme.SecondaryText
import com.nndai.myhome.data.model.Device
import com.nndai.myhome.data.remote.DeviceHealthStatus
import com.nndai.myhome.data.repository.DeviceManagerRepository
import com.nndai.myhome.presentation.device.components.ConfirmDialog
import com.nndai.myhome.presentation.device.components.DeviceHealthIndicator

@Composable
fun DeviceManagementScreen(
    deviceRepository: DeviceManagerRepository,
    isLoggedIn: Boolean,
    onNavigateToLogin: () -> Unit,
    onAddDeviceClick: () -> Unit,
    onNavigateToDevice: (String, String) -> Unit,
    onManageMembers: (String, String, String) -> Unit = { _, _, _ -> }
) {
    val devices by deviceRepository.devices.collectAsState()
    val lastError by deviceRepository.lastError.collectAsState()

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var deviceToRename by remember { mutableStateOf<Device?>(null) }
    var deviceToDelete by remember { mutableStateOf<Device?>(null) }
    var deviceToLeave by remember { mutableStateOf<Device?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // ── Header with Refresh ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Devices",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "${devices.size} device(s) registered",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = {
                        scope.launch {
                            isRefreshing = true
                            val r = deviceRepository.fetchDevices()
                            isRefreshing = false
                            if (r.isSuccess) {
                                snackbarHostState.showSnackbar("Đã đồng bộ danh sách thiết bị")
                            } else {
                                snackbarHostState.showSnackbar(
                                    r.exceptionOrNull()?.message ?: "Lỗi đồng bộ"
                                )
                            }
                        }
                    },
                    enabled = !isRefreshing
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Làm mới danh sách",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // ── Device List ──
            AnimatedVisibility(
                visible = devices.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(devices, key = { it.device_id }) { device ->
                        DeviceListItem(
                            device = device,
                            onClick = {
                                if (isLoggedIn) onNavigateToDevice(device.device_id, device.profile)
                                else onNavigateToLogin()
                            },
                            onRenameClick = { deviceToRename = device },
                            onDeleteClick = { deviceToDelete = device },
                            onManageMembers = {
                                onManageMembers(device.id, device.device_id, device.name)
                            },
                            onLeaveDevice = { deviceToLeave = device }
                        )
                    }
                }
            }

            // ── Error State ──
            if (lastError != null && devices.isEmpty()) {
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
                // ── Empty State ──
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

    // ── Rename Dialog ──
    deviceToRename?.let { targetDevice ->
        RenameDeviceDialog(
            device = targetDevice,
            onDismiss = { deviceToRename = null },
            onConfirm = { newName ->
                val devId = targetDevice.device_id
                deviceToRename = null
                scope.launch {
                    isBusy = true
                    val r = deviceRepository.updateDeviceName(devId, newName)
                    isBusy = false
                    if (r.isSuccess) {
                        snackbarHostState.showSnackbar("Đã đổi tên thiết bị thành công")
                    } else {
                        snackbarHostState.showSnackbar(
                            r.exceptionOrNull()?.message ?: "Lỗi đổi tên"
                        )
                    }
                }
            }
        )
    }

    // ── Delete Dialog ──
    deviceToDelete?.let { targetDevice ->
        ConfirmDialog(
            title = "Xóa thiết bị",
            message = "Bạn có chắc chắn muốn xóa thiết bị '${targetDevice.name}' " +
                    "(${targetDevice.device_id}) khỏi tài khoản?\n\n" +
                    "Thiết bị sẽ bị hủy liên kết và xóa dữ liệu.",
            confirmText = "Xóa thiết bị",
            isDangerous = true,
            requiredInput = "delete",
            onConfirm = {
                val devId = targetDevice.device_id
                deviceToDelete = null
                scope.launch {
                    isBusy = true
                    val r = deviceRepository.removeDevice(devId)
                    isBusy = false
                    if (r.isSuccess) {
                        snackbarHostState.showSnackbar("Đã xóa thiết bị thành công")
                    } else {
                        snackbarHostState.showSnackbar(
                            r.exceptionOrNull()?.message ?: "Lỗi khi xóa thiết bị"
                        )
                    }
                }
            },
            onDismiss = { deviceToDelete = null }
        )
    }

    // ── Leave Device Dialog (member rời thiết bị được chia sẻ) ──
    deviceToLeave?.let { targetDevice ->
        ConfirmDialog(
            title = "Rời khỏi thiết bị",
            message = "Bạn sẽ mất toàn bộ quyền truy cập vào " +
                    "'${targetDevice.name}' (${targetDevice.device_id}).",
            confirmText = "Rời thiết bị",
            isDangerous = true,
            icon = Icons.Filled.LinkOff,
            onConfirm = {
                val uuid = targetDevice.id
                val key = targetDevice.device_id
                deviceToLeave = null
                scope.launch {
                    isBusy = true
                    val r = deviceRepository.leaveSharedDevice(key, uuid)
                    if (r.isSuccess) {
                        snackbarHostState.showSnackbar("Đã rời khỏi thiết bị")
                    } else {
                        snackbarHostState.showSnackbar(r.exceptionOrNull()?.message ?: "Không thể rời thiết bị")
                    }
                    isBusy = false
                }
            },
            onDismiss = { deviceToLeave = null }
        )
    }

    // ── Busy Overlay ──
    if (isBusy) {
        Dialog(onDismissRequest = {}) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            ) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Đang xử lý...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Device List Item
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DeviceListItem(
    device: Device,
    onClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onManageMembers: () -> Unit,
    onLeaveDevice: () -> Unit
) {
    // Profile-based icon & accent color
    val icon = when (device.profile.lowercase()) {
        "pump" -> Icons.Filled.WaterDrop
        "fan" -> Icons.Filled.ModeFanOff
        "lamp", "switch", "remote_switch" -> Icons.Filled.Lightbulb
        else -> Icons.Filled.DeviceUnknown
    }
    val accentColor = when (device.profile.lowercase()) {
        "pump" -> CyanBlue
        "fan" -> GreenOk
        "lamp", "switch", "remote_switch" -> OrangeWarning
        else -> SecondaryText
    }

    // Quyền của chính mình trên thiết bị này (từ RPC get_my_devices).
    // role trống = dữ liệu cache cũ trước migration → mặc định coi như OWNER
    // để không khóa nhầm menu (sẽ tự chuẩn sau lần fetch đầu).
    val myRole = device.role?.uppercase()
    val canManage = myRole == null || com.nndai.myhome.data.model.DeviceRoles.canManage(myRole)

    // Ownership check
    val currentUserId = remember {
        try {
            com.nndai.myhome.data.remote.SupabaseConfig.client.auth
                .currentSessionOrNull()?.user?.id
        } catch (_: Exception) { null }
    }
    val isTransferred = device.isTransferred(currentUserId)

    // Real-time health state
    val handshakeMgr = remember {
        com.nndai.myhome.data.di.PumpRepositoryProvider.provideDeviceHandshakeManager()
    }
    val healthStateFlow = remember(device.device_id) {
        handshakeMgr.registerDevice(device.device_id)
    }
    val healthState by healthStateFlow.collectAsState()

    var showMenu by remember { mutableStateOf(false) }

    // Resolve status label & color from theme palette
    val statusText: String
    val statusColor: androidx.compose.ui.graphics.Color

    if (isTransferred) {
        statusText = "Đã đổi chủ"
        statusColor = OrangeWarning
    } else {
        when (healthState) {
            is DeviceHealthStatus.Online -> {
                statusText = "Online"
                statusColor = GreenOk
            }
            is DeviceHealthStatus.Handshaking -> {
                statusText = "Connecting..."
                statusColor = OrangeWarning
            }
            is DeviceHealthStatus.Offline -> {
                statusText = "Offline"
                statusColor = MaterialTheme.colorScheme.onSurfaceVariant
            }
            else -> {
                statusText = "Unknown"
                statusColor = MaterialTheme.colorScheme.onSurfaceVariant
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ── Profile Icon ──
            Surface(
                shape = MaterialTheme.shapes.small,
                color = accentColor.copy(alpha = 0.12f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // ── Device Info ──
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
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

                Spacer(modifier = Modifier.width(8.dp))

                // ── Role badge (thiết bị được chia sẻ với mình) ──
                if (!device.role.isNullOrBlank() &&
                    device.role.uppercase() != "OWNER" &&
                    !device.isTransferred(currentUserId)
                ) {
                    val roleColor = when (device.role.uppercase()) {
                        com.nndai.myhome.data.model.DeviceRoles.ADMIN -> OrangeWarning
                        else -> CyanBlue
                    }
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = roleColor.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = com.nndai.myhome.data.model.DeviceRoles.label(device.role),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = FontWeight.Medium,
                            color = roleColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }

            // ── Status Badge ──
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = statusColor.copy(alpha = 0.12f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
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
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        fontWeight = FontWeight.Medium,
                        color = statusColor
                    )
                }
            }

            // ── More Options ──
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "Tùy chọn thiết bị",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    shape = MaterialTheme.shapes.medium,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 4.dp
                ) {
                    // Quản lý thành viên — chỉ OWNER/ADMIN
                    if (canManage) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Thành viên",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Group,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                            onClick = {
                                showMenu = false
                                onManageMembers()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Đổi tên",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Edit,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                            onClick = {
                                showMenu = false
                                onRenameClick()
                            }
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Xóa thiết bị",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                            onClick = {
                                showMenu = false
                                onDeleteClick()
                            }
                        )
                    } else if (!device.role.isNullOrBlank()) {
                        // Member/Viewer của thiết bị chia sẻ: chỉ được tự rời
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Rời khỏi thiết bị",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.LinkOff,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                            onClick = {
                                showMenu = false
                                onLeaveDevice()
                            }
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Rename Device Dialog
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun RenameDeviceDialog(
    device: Device,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var newName by remember { mutableStateOf(device.name) }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Đổi tên thiết bị",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Nhập tên mới cho thiết bị này:",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = device.device_id,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = {
                        newName = it
                        if (it.isNotBlank()) errorText = null
                    },
                    label = { Text("Tên thiết bị") },
                    singleLine = true,
                    isError = errorText != null,
                    supportingText = errorText?.let {
                        { Text(it, color = MaterialTheme.colorScheme.error) }
                    },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth()) {
                // Full-width actions: Cancel left, Save right
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text("Hủy")
                }
                Button(
                    onClick = {
                        if (newName.trim().isBlank()) {
                            errorText = "Tên không được để trống"
                        } else {
                            onConfirm(newName.trim())
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.weight(1.4f)
                ) {
                    Text("Lưu", fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = null,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large
    )
}
