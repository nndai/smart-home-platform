package com.nndai.myhome.presentation.device.share

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nndai.myhome.R
import com.nndai.myhome.core.theme.GreenOk
import com.nndai.myhome.core.theme.OrangeWarning
import com.nndai.myhome.data.model.ActiveInvite
import com.nndai.myhome.data.model.CreatedInvite
import com.nndai.myhome.data.model.DeviceMember
import com.nndai.myhome.data.model.DeviceRoles
import com.nndai.myhome.presentation.device.components.ConfirmDialog
import io.github.jan.supabase.auth.auth

/**
 * Quản lý chia sẻ của MỘT thiết bị: danh sách thành viên + mã invite đang chạy.
 * Chỉ OWNER/ADMIN được vào màn này (route guard ở nơi điều hướng).
 */
@Composable
fun MemberManageScreen(
    deviceUuid: String,
    deviceKey: String,
    deviceName: String,
    onNavigateBack: () -> Unit
) {
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
    val viewModel: MemberManageViewModel = viewModel(
        key = deviceUuid,
        factory = remember {
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(
                    modelClass: Class<T>
                ): T = MemberManageViewModel(
                    deviceUuid = deviceUuid,
                    application = appContext
                ) as T
            }
        }
    )
    val members by viewModel.members.collectAsState()
    val invites by viewModel.invites.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val message by viewModel.message.collectAsState()
    val createdInvite by viewModel.createdInvite.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateInvite by remember { mutableStateOf(false) }
    var memberToRemove by remember { mutableStateOf<DeviceMember?>(null) }
    var inviteToRevoke by remember { mutableStateOf<ActiveInvite?>(null) }
    var memberForRoleChange by remember { mutableStateOf<DeviceMember?>(null) }

    // Vai trò của chính mình (từ members list — server trả đủ mọi member)
    val myUserId = remember {
        try {
            com.nndai.myhome.data.remote.SupabaseConfig.client.auth.currentSessionOrNull()?.user?.id
        } catch (_: Exception) { null }
    }
    val myRole = members.firstOrNull { it.user_id == myUserId }?.role ?: DeviceRoles.OWNER

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // ── Header ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.members_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = deviceName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Button(
                    onClick = { showCreateInvite = true },
                    enabled = !isBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = MaterialTheme.shapes.small
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(stringResource(R.string.share_button))
                }
            }

            if (isLoading && members.isEmpty()) {
                Spacer(modifier = Modifier.height(48.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // ── Members ──
                    item { SectionLabel(stringResource(R.string.share_section_members, members.size)) }
                    items(members, key = { it.user_id }) { member ->
                        MemberRow(
                            member = member,
                            isMe = member.user_id == myUserId,
                            myRole = myRole,
                            onChangeRole = { memberForRoleChange = member },
                            onRemove = { memberToRemove = member }
                        )
                    }

                    // ── Active invites ──
                    item { SectionLabel(stringResource(R.string.share_section_invites, invites.size)) }
                    if (invites.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.share_no_active_codes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                    items(invites, key = { it.code }) { invite ->
                        InviteRow(
                            invite = invite,
                            onRevoke = { inviteToRevoke = invite }
                        )
                    }

                    item { Spacer(modifier = Modifier.height(24.dp)) }
                }
            }
        }
    }

    // ── Create invite dialog (role picker → code display) ──
    if (showCreateInvite) {
        ShareInviteDialog(
            createdInvite = createdInvite,
            isCreating = isBusy,
            onCreate = { role ->
                viewModel.createInvite(role)
            },
            onDismiss = {
                showCreateInvite = false
                viewModel.dismissCreatedInvite()
            }
        )
    }

    // ── Remove member confirm ──
    memberToRemove?.let { target ->
        val displayName = target.email.orEmpty().ifBlank { shortId(target.user_id) }
        ConfirmDialog(
            title = stringResource(R.string.member_remove_title),
            message = stringResource(R.string.member_remove_message, displayName),
            confirmText = stringResource(R.string.member_remove),
            isDangerous = true,
            onConfirm = {
                viewModel.removeMember(target.user_id)
                memberToRemove = null
            },
            onDismiss = { memberToRemove = null }
        )
    }

    // ── Revoke invite confirm ──
    inviteToRevoke?.let { invite ->
        ConfirmDialog(
            title = stringResource(R.string.revoke_invite_title),
            message = stringResource(R.string.revoke_invite_message, invite.code),
            confirmText = stringResource(R.string.invite_revoke),
            isDangerous = true,
            icon = Icons.Filled.LinkOff,
            onConfirm = {
                viewModel.revokeInvite(invite.code)
                inviteToRevoke = null
            },
            onDismiss = { inviteToRevoke = null }
        )
    }

    // ── Change role dialog ──
    memberForRoleChange?.let { target ->
        RoleChangeDialog(
            member = target,
            isBusy = isBusy,
            onConfirm = { newRole ->
                viewModel.updateMemberRole(target.user_id, newRole)
                memberForRoleChange = null
            },
            onDismiss = { memberForRoleChange = null }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp)
    )
}

private fun shortId(userId: String): String =
    if (userId.length <= 10) userId else userId.take(8) + "..."

/** Localized role label (DeviceRoles.label is non-composable, cannot resolve resources). */
@Composable
private fun roleLabel(role: String): String = stringResource(
    when (role.uppercase()) {
        DeviceRoles.OWNER -> R.string.role_owner
        DeviceRoles.ADMIN -> R.string.role_admin
        DeviceRoles.MEMBER -> R.string.role_member
        else -> R.string.role_viewer
    }
)

/** Role accent color theo theme palette. */
private fun roleColor(role: String): Color = when (role.uppercase()) {
    DeviceRoles.OWNER -> GreenOk
    DeviceRoles.ADMIN -> OrangeWarning
    else -> MaterialThemeStatic.chipDefault
}

// Tránh gọi MaterialTheme trong hàm non-composable: dùng màu tĩnh nhạt
private object MaterialThemeStatic {
    val chipDefault = Color(0xFF4FC3F7)
}

@Composable
private fun MemberRow(
    member: DeviceMember,
    isMe: Boolean,
    myRole: String,
    onChangeRole: () -> Unit,
    onRemove: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val canManageTarget = DeviceRoles.canManage(myRole) &&
            member.role != DeviceRoles.OWNER && !isMe

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar chữ đầu
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .background(roleColor(member.role).copy(alpha = 0.14f), CircleShape)
            ) {
                Text(
                    text = (member.email?.firstOrNull()?.uppercaseChar()?.toString())
                        ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = roleColor(member.role)
                )
            }
            Spacer(modifier = Modifier.size(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = member.email.orEmpty().ifBlank { shortId(member.user_id) } +
                            if (isMe) stringResource(R.string.member_me_suffix) else "",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = roleLabel(member.role),
                    style = MaterialTheme.typography.labelMedium,
                    color = roleColor(member.role)
                )
            }

            if (canManageTarget) {
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.PersonRemove,
                        contentDescription = stringResource(R.string.member_options_desc),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.member_change_role)) },
                            onClick = {
                                showMenu = false
                                onChangeRole()
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.member_remove), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                showMenu = false
                                onRemove()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InviteRow(invite: ActiveInvite, onRevoke: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Group,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = invite.code,
                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.invite_single_use, roleLabel(invite.role)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = onRevoke,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                shape = MaterialTheme.shapes.small
            ) {
                Text(stringResource(R.string.invite_revoke), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun RoleChangeDialog(
    member: DeviceMember,
    isBusy: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(member.role) }

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
                        Icons.Filled.Group,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.role_change_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = member.email.orEmpty().ifBlank { shortId(member.user_id) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    DeviceRoles.ADMIN to R.string.role_label_admin,
                    DeviceRoles.MEMBER to R.string.role_label_member,
                    DeviceRoles.VIEWER to R.string.role_label_viewer
                ).forEach { (role, labelRes) ->
                    FilterChip(
                        selected = selected == role,
                        enabled = !isBusy,
                        onClick = { selected = role },
                        label = {
                            Text(
                                stringResource(labelRes),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    )
                }
            }
        },
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth()) {
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
                Button(
                    onClick = { onConfirm(selected) },
                    enabled = !isBusy && selected != member.role,
                    modifier = Modifier.weight(1.4f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = MaterialTheme.shapes.small
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
