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
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeviceUnknown
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.ModeFanOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch
import com.nndai.myhome.R
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

    // ── Redeem invite (nhập mã chia sẻ) ──
    val shareRepo =
        remember { com.nndai.myhome.data.di.PumpRepositoryProvider.provideDeviceShareRepository() }
    var showRedeemDialog by remember { mutableStateOf(false) }
    var isRedeeming by remember { mutableStateOf(false) }
    var redeemedDevice by remember {
        mutableStateOf<com.nndai.myhome.data.model.RedeemedDevice?>(null)
    }
    var redeemError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // ── App Header: title + actions ──
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.devices_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isLoggedIn)
                            stringResource(R.string.devices_registered_count, devices.size)
                        else stringResource(R.string.home_sign_in_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Refresh — bare icon
                    val syncedMsg = stringResource(R.string.msg_devices_synced)
                    val syncFailedMsg = stringResource(R.string.msg_sync_failed)
                    IconButton(
                        onClick = {
                            scope.launch {
                                isRefreshing = true
                                val r = deviceRepository.fetchDevices()
                                isRefreshing = false
                                if (r.isSuccess) snackbarHostState.showSnackbar(syncedMsg)
                                else snackbarHostState.showSnackbar(
                                    r.exceptionOrNull()?.message ?: syncFailedMsg
                                )
                            }
                        },
                        enabled = !isRefreshing && isLoggedIn,
                        modifier = Modifier.size(40.dp)
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.system_refresh),
                                tint = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    if (isLoggedIn) {
                        // Nhập mã chia sẻ — bare icon
                        IconButton(
                            onClick = { showRedeemDialog = true },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.GroupAdd,
                                contentDescription = stringResource(R.string.redeem_entry),
                                tint = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        // Add device — large bare icon
                        IconButton(
                            onClick = onAddDeviceClick,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = stringResource(R.string.add_device),
                                modifier = Modifier.size(30.dp),
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    } else {
                        // Guest — sign-in pill
                        Button(
                            onClick = onNavigateToLogin,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            shape = MaterialTheme.shapes.small,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                            modifier = Modifier.height(40.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Login,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.profile_sign_in),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // ── Guest banner ──
            androidx.compose.animation.AnimatedVisibility(visible = !isLoggedIn) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .clickable(onClick = onNavigateToLogin),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    imageVector = Icons.Filled.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.profile_guest),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.home_sign_in_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

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
                        text = stringResource(R.string.devices_empty_title),
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
        val renamedMsg = stringResource(R.string.msg_renamed)
        val renameFailedMsg = stringResource(R.string.msg_rename_failed)
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
                        snackbarHostState.showSnackbar(renamedMsg)
                    } else {
                        snackbarHostState.showSnackbar(
                            r.exceptionOrNull()?.message ?: renameFailedMsg
                        )
                    }
                }
            }
        )
    }

    // ── Delete Dialog ──
    deviceToDelete?.let { targetDevice ->
        val deletedMsg = stringResource(R.string.msg_device_deleted)
        val deleteFailedMsg = stringResource(R.string.msg_delete_failed)
        ConfirmDialog(
            title = stringResource(R.string.delete_device_title),
            message = stringResource(
                R.string.delete_device_message,
                targetDevice.name,
                targetDevice.device_id
            ),
            confirmText = stringResource(R.string.delete_device_action),
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
                        snackbarHostState.showSnackbar(deletedMsg)
                    } else {
                        snackbarHostState.showSnackbar(
                            r.exceptionOrNull()?.message ?: deleteFailedMsg
                        )
                    }
                }
            },
            onDismiss = { deviceToDelete = null }
        )
    }

    // ── Leave Device Dialog (member rời thiết bị được chia sẻ) ──
    deviceToLeave?.let { targetDevice ->
        val leftMsg = stringResource(R.string.leave_success)
        val leaveFailedMsg = stringResource(R.string.leave_failed)
        ConfirmDialog(
            title = stringResource(R.string.leave_device_title),
            message = stringResource(
                R.string.leave_device_message,
                targetDevice.name,
                targetDevice.device_id
            ),
            confirmText = stringResource(R.string.leave_device_action),
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
                        snackbarHostState.showSnackbar(leftMsg)
                    } else {
                        snackbarHostState.showSnackbar(r.exceptionOrNull()?.message ?: leaveFailedMsg)
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
                        text = stringResource(R.string.processing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }

    // ── Redeem Invite Dialog ──
    if (showRedeemDialog) {
        com.nndai.myhome.presentation.device.share.RedeemInviteDialog(
            isBusy = isRedeeming,
            successDevice = redeemedDevice,
            serverError = redeemError,
            onDismiss = {
                showRedeemDialog = false
                redeemedDevice = null
                redeemError = null
            },
            onSuccessShown = {
                showRedeemDialog = false
                redeemedDevice = null
                scope.launch { deviceRepository.fetchDevices() }
            },
            onRedeem = { code ->
                scope.launch {
                    isRedeeming = true
                    shareRepo.redeemInvite(code)
                        .onSuccess { result ->
                            redeemedDevice = result
                            deviceRepository.fetchDevices()
                        }
                        .onFailure { redeemError = it.message }
                    isRedeeming = false
                }
            }
        )
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
    // Custom brand icons (multicolor vectors) — rendered without tint
    val customIconRes = when (device.profile.lowercase()) {
        "pump" -> R.drawable.ic_pump_device
        "remote_switch" -> R.drawable.ic_remote_switch_device
        else -> null
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
            statusText = stringResource(R.string.devices_status_transferred)
        statusColor = OrangeWarning
    } else {
        when (healthState) {
            is DeviceHealthStatus.Online -> {
                statusText = stringResource(R.string.devices_status_online)
                statusColor = GreenOk
            }
            is DeviceHealthStatus.Handshaking -> {
                statusText = stringResource(R.string.devices_status_connecting)
                statusColor = OrangeWarning
            }
            is DeviceHealthStatus.Offline -> {
                statusText = stringResource(R.string.devices_status_offline)
                statusColor = MaterialTheme.colorScheme.onSurfaceVariant
            }
            else -> {
                statusText = stringResource(R.string.devices_status_unknown)
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
                    if (customIconRes != null) {
                        Icon(
                            painter = painterResource(customIconRes),
                            contentDescription = null,
                            tint = Color.Unspecified,   // giữ nguyên màu vector gốc
                            modifier = Modifier.size(32.dp)
                        )
                    } else {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
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

            // ── Badges Column (Status + Role with equal dynamic width and centered content) ──
            Column(
                modifier = Modifier.width(IntrinsicSize.Max),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                // ── Status Badge ──
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraSmall,
                    color = statusColor.copy(alpha = 0.12f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        DeviceHealthIndicator(
                            healthState = healthState,
                            isTransferred = isTransferred,
                            dotSize = 6.dp,
                            iconSize = 10.dp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = FontWeight.Medium,
                            color = statusColor
                        )
                    }
                }

                // ── Role badge (thiết bị được chia sẻ với mình: Thành viên, Quản lý) ──
                if (!device.role.isNullOrBlank() &&
                    device.role.uppercase() != "OWNER" &&
                    !device.isTransferred(currentUserId)
                ) {
                    val roleColor = when (device.role.uppercase()) {
                        com.nndai.myhome.data.model.DeviceRoles.ADMIN -> OrangeWarning
                        else -> CyanBlue
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraSmall,
                        color = roleColor.copy(alpha = 0.12f)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = com.nndai.myhome.data.model.DeviceRoles.label(device.role),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                fontWeight = FontWeight.Medium,
                                color = roleColor,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            // ── More Options ──
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.device_options_desc),
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
                                    stringResource(R.string.menu_members),
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
                                    stringResource(R.string.menu_rename),
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
                                    stringResource(R.string.menu_delete_device),
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
                                    stringResource(R.string.menu_leave_device),
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
    val context = LocalContext.current

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
                    text = stringResource(R.string.rename_title),
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
                    text = stringResource(R.string.rename_hint),
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
                    label = { Text(stringResource(R.string.rename_label)) },
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
                    Text(stringResource(R.string.action_cancel))
                }
                Spacer(modifier = Modifier.width(16.dp))
                Button(
                    onClick = {
                        if (newName.trim().isBlank()) {
                            errorText = context.getString(R.string.rename_error_empty)
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
                    Text(stringResource(R.string.action_save), fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = null,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large
    )
}
