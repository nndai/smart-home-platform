package com.nndai.myhome.presentation.device.share

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nndai.myhome.data.model.DeviceRoles
import com.nndai.myhome.data.model.RedeemedDevice

/**
 * Nhập mã invite (18 hex chars) để tham gia một thiết bị được chia sẻ.
 * Thành công trả [RedeemedDevice] qua [onSuccess] — caller refresh + snackbar.
 */
@Composable
fun RedeemInviteDialog(
    isBusy: Boolean,
    onRedeem: (String) -> Unit,
    onDismiss: () -> Unit,
    onSuccessShown: () -> Unit,
    successDevice: RedeemedDevice? = null,
    serverError: String? = null
) {
    var code by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
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
                        imageVector = Icons.Outlined.Password,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Nhập mã chia sẻ",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        text = {
            if (successDevice == null) {
                Column {
                    Text(
                        text = "Nhập mã do chủ thiết bị gửi cho bạn:",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { raw ->
                            // Chuẩn hóa: lowercase, chỉ hex, tối đa 18 ký tự
                            val filtered = raw.lowercase().filter { it.isDigit() || it in 'a'..'f' }.take(18)
                            code = filtered
                            if (filtered.length == 18) errorText = null
                        },
                        label = { Text("Mã chia sẻ") },
                        singleLine = true,
                        isError = errorText != null || serverError != null,
                        supportingText = (errorText ?: serverError)?.let {
                            { Text(it, color = MaterialTheme.colorScheme.error) }
                        },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp
                        ),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Đã thêm thiết bị:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = successDevice.device_name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Vai trò của bạn: ${roleLabel(successDevice.role)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (successDevice == null) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isBusy,
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
                            when {
                                code.length < 18 -> errorText = "Mã phải đủ 18 ký tự"
                                else -> onRedeem(code)
                            }
                        },
                        enabled = !isBusy,
                        modifier = Modifier.weight(1.4f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(if (isBusy) "Đang xử lý..." else "Thêm thiết bị", fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = onSuccessShown,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text("Xong", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        dismissButton = null,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large
    )
}

private fun roleLabel(role: String): String = DeviceRoles.label(role)
