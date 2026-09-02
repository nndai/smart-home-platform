package com.nndai.myhome.presentation.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nndai.myhome.BuildConfig
import com.nndai.myhome.data.repository.AppUpdateRepository
import com.nndai.myhome.data.repository.AppVersionResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface AppUpdateState {
    object Idle : AppUpdateState
    object Checking : AppUpdateState
    data class UpdateAvailable(val updateInfo: AppVersionResponse) : AppUpdateState
    data class Downloading(val progress: Float) : AppUpdateState
    data class ReadyToInstall(val apkUri: Uri) : AppUpdateState
    data class Error(val message: String) : AppUpdateState
}

class AppUpdateViewModel(
    private val repository: AppUpdateRepository = AppUpdateRepository()
) : ViewModel() {

    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    private var downloadId: Long = -1

    fun checkForUpdates() {
        if (_state.value is AppUpdateState.Checking || _state.value is AppUpdateState.UpdateAvailable) return

        _state.value = AppUpdateState.Checking
        viewModelScope.launch {
            val latestVersion = repository.getLatestVersion()
            if (latestVersion != null && latestVersion.version_code > BuildConfig.VERSION_CODE) {
                _state.value = AppUpdateState.UpdateAvailable(latestVersion)
            } else {
                _state.value = AppUpdateState.Idle
            }
        }
    }

    fun dismissUpdate() {
        _state.value = AppUpdateState.Idle
    }

    fun downloadUpdate(context: Context, downloadUrl: String, versionName: String) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = Uri.parse(downloadUrl)

        // Delete old update file if exists
        val fileName = "update_$versionName.apk"
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (file.exists()) {
            file.delete()
        }

        val request = DownloadManager.Request(uri)
            .setTitle("Downloading App Update")
            .setDescription("Version $versionName")
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        try {
            downloadId = downloadManager.enqueue(request)
            _state.value = AppUpdateState.Downloading(0f)
            trackDownloadProgress(context, downloadManager, fileName)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            _state.value = AppUpdateState.Error("Failed to start download")
        }
    }

    private fun trackDownloadProgress(context: Context, downloadManager: DownloadManager, fileName: String) {
        viewModelScope.launch {
            var downloading = true
            while (downloading) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val statusColumn = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val bytesDownloadedColumn = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val bytesTotalColumn = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    
                    if (statusColumn >= 0 && bytesDownloadedColumn >= 0 && bytesTotalColumn >= 0) {
                        val status = cursor.getInt(statusColumn)
                        val bytesDownloaded = cursor.getInt(bytesDownloadedColumn)
                        val bytesTotal = cursor.getInt(bytesTotalColumn)

                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            downloading = false
                            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
                            val fileUri = androidx.core.content.FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file
                            )
                            _state.value = AppUpdateState.ReadyToInstall(fileUri)
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            downloading = false
                            _state.value = AppUpdateState.Error("Download failed")
                        } else {
                            val progress = if (bytesTotal > 0) bytesDownloaded.toFloat() / bytesTotal.toFloat() else 0f
                            _state.value = AppUpdateState.Downloading(progress)
                        }
                    }
                }
                cursor?.close()
                if (downloading) {
                    delay(500)
                }
            }
        }
    }

    companion object {
        private const val TAG = "AppUpdateViewModel"
    }
}
