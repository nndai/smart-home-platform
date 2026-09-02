package com.nndai.myhome.presentation.update

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun AppUpdateDialog(
    viewModel: AppUpdateViewModel
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state) {
        if (state is AppUpdateState.ReadyToInstall) {
            val apkUri = (state as AppUpdateState.ReadyToInstall).apkUri
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
            // After triggering install, we can reset state or keep it in ReadyToInstall
            // Normally user will leave the app to install. 
            // If they cancel, they might need to restart app to see prompt again.
        }
    }

    if (state is AppUpdateState.UpdateAvailable || state is AppUpdateState.Downloading || state is AppUpdateState.ReadyToInstall || state is AppUpdateState.Error) {
        
        val isMandatory = (state as? AppUpdateState.UpdateAvailable)?.updateInfo?.is_mandatory == true ||
                (state as? AppUpdateState.Downloading) != null || 
                (state as? AppUpdateState.ReadyToInstall) != null
                // If downloading or ready, we don't let them dismiss usually, but let's base it on actual data if possible.
                // For simplicity, we just check if it's mandatory. Wait, when in Downloading state, we lose the updateInfo in current sealed class.
                // Let's just lock the dialog if it's downloading to prevent issues.

        val lockDialog = isMandatory || state is AppUpdateState.Downloading

        Dialog(
            onDismissRequest = {
                if (!lockDialog) {
                    viewModel.dismissUpdate()
                }
            },
            properties = DialogProperties(
                dismissOnBackPress = !lockDialog,
                dismissOnClickOutside = !lockDialog
            )
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        text = "Bản cập nhật mới",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    when (val currentState = state) {
                        is AppUpdateState.UpdateAvailable -> {
                            Text(
                                text = "Phiên bản ${currentState.updateInfo.version_name} đã sẵn sàng.",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = currentState.updateInfo.release_notes,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(24.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                if (!currentState.updateInfo.is_mandatory) {
                                    TextButton(onClick = { viewModel.dismissUpdate() }) {
                                        Text("Để sau")
                                    }
                                }
                                Button(
                                    onClick = {
                                        viewModel.downloadUpdate(
                                            context = context,
                                            downloadUrl = currentState.updateInfo.download_url,
                                            versionName = currentState.updateInfo.version_name
                                        )
                                    }
                                ) {
                                    Text("Cập nhật ngay")
                                }
                            }
                        }
                        is AppUpdateState.Downloading -> {
                            Text(
                                text = "Đang tải bản cập nhật...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            LinearProgressIndicator(
                                progress = { currentState.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "${(currentState.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        is AppUpdateState.ReadyToInstall -> {
                            Text(
                                text = "Đã tải xong. Đang mở trình cài đặt...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(currentState.apkUri, "application/vnd.android.package-archive")
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    }
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Cài đặt thủ công")
                            }
                        }
                        is AppUpdateState.Error -> {
                            Text(
                                text = "Có lỗi xảy ra: ${currentState.message}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { viewModel.dismissUpdate() }) {
                                    Text("Đóng")
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }
        }
    }
}
