package com.nndai.myhome.presentation.device.share

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nndai.myhome.data.model.CreatedInvite
import com.nndai.myhome.data.model.DeviceRoles

/**
 * Step 1: pick role for the new invite.
 * Step 2: show the generated code with copy/share actions.
 */
@Composable
fun ShareInviteDialog(
    createdInvite: CreatedInvite?,
    isCreating: Boolean,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current

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
                        imageVector = Icons.Outlined.Share,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = if (createdInvite == null) "Tạo mã chia sẻ"
                    else "Mã chia sẻ đã sẵn sàng",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        text = {
            if (createdInvite == null) {
                RolePickerContent(isCreating = isCreating, onPick = onCreate)
            } else {
                CreatedCodeContent(createdInvite = createdInvite)
            }
        },
        confirmButton = {
            if (createdInvite != null) {
                val context = androidx.compose.ui.platform.LocalContext.current
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            // Share sheet: gửi mã qua bất kỳ app nhắn tin nào
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    android.content.Intent.EXTRA_TEXT,
                                    "Mã chia sẻ thiết bị \"${createdInvite.device_name}\": ${createdInvite.code}\n" +
                                            "Mở app My Home → Nhập mã chia sẻ để thêm thiết bị."
                                )
                            }
                            context.startActivity(
                                android.content.Intent.createChooser(send, "Chia sẻ mã")
                            )
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text("Chia sẻ", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(createdInvite.code))
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text("Copy")
                    }
                }
            }
        },
        dismissButton = null,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large
    )
}

@Composable
private fun RolePickerContent(isCreating: Boolean, onPick: (String) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Chọn vai trò cho người được chia sẻ:",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                DeviceRoles.ADMIN to "Quản lý",
                DeviceRoles.MEMBER to "Thành viên",
                DeviceRoles.VIEWER to "Chỉ xem"
            ).forEach { (role, label) ->
                FilterChip(
                    selected = false,
                    onClick = { onPick(role) },
                    enabled = !isCreating,
                    label = {
                        Text(
                            label,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                )
            }
        }
        Text(
            text = "• Quản lý: điều khiển + mời người khác\n" +
                    "• Thành viên: điều khiển thiết bị\n" +
                    "• Chỉ xem: chỉ xem trạng thái",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )
    }
}

@Composable
private fun CreatedCodeContent(createdInvite: CreatedInvite) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "\"${createdInvite.device_name}\"",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Text(
                text = createdInvite.code,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp
                ),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
        Text(
            text = "Gửi mã này cho người thân. Mã dùng một lần và có hiệu lực đến khi thu hồi.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
