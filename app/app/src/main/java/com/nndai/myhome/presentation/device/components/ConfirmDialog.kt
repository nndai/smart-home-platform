package com.nndai.myhome.presentation.device.components

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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Confirmation dialog for destructive / important actions
 * (Delete device, Reboot, Factory Reset...).
 *
 * Visual style is shared with [RenameDeviceDialog] so all CRUD dialogs
 * look consistent across the app.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "Confirm",
    dismissText: String = "Cancel",
    isDangerous: Boolean = false,
    requiredInput: String? = null,
    icon: ImageVector? = if (isDangerous) Icons.Outlined.DeleteOutline else null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }

    val accent = if (isDangerous) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.primary
    val accentContainer = if (isDangerous)
        MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
    else MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (icon != null) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(56.dp)
                            .background(color = accentContainer, shape = CircleShape)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
                Text(
                    text = title,
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
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (requiredInput != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    CompactTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        label = "Gõ chữ '$requiredInput' để xác nhận",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth()) {
                // Full-width actions: Cancel left, Confirm right
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(dismissText)
                }
                Spacer(modifier = Modifier.weight(0.1f))
                Button(
                    onClick = onConfirm,
                    enabled = requiredInput == null || inputText == requiredInput,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor = if (isDangerous) MaterialTheme.colorScheme.onError
                        else MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.weight(1.4f)
                ) {
                    Text(confirmText, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = null,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large
    )
}
