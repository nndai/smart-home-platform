package com.nndai.myhome.presentation.update

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nndai.myhome.BuildConfig
import com.nndai.myhome.data.repository.AppUpdateRepository
import com.nndai.myhome.data.repository.AppVersionResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface AppUpdateState {
    object Idle : AppUpdateState
    object Checking : AppUpdateState
    data class UpdateAvailable(val updateInfo: AppVersionResponse) : AppUpdateState
    data class Downloading(val progress: Float?) : AppUpdateState
    data class ReadyToInstall(val apkUri: Uri) : AppUpdateState
    data class Error(val message: String) : AppUpdateState
}

class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppUpdateRepository()
    private val manager = application.getSystemService(DownloadManager::class.java)
    private val preferences = application.getSharedPreferences("app_update", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state = _state.asStateFlow()

    private var downloadId = preferences.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD)
    private var downloadVersion = preferences.getInt(KEY_VERSION, 0)
    private var startingDownload = false

    fun checkForUpdates(isManual: Boolean = false, context: Context? = null) {
        if (startingDownload || downloadId != NO_DOWNLOAD ||
            _state.value is AppUpdateState.Checking || _state.value is AppUpdateState.UpdateAvailable
        ) return

        _state.value = AppUpdateState.Checking
        if (isManual && context != null) {
            Toast.makeText(context, "Đang kiểm tra bản cập nhật...", Toast.LENGTH_SHORT).show()
        }
        viewModelScope.launch {
            val latest = repository.getLatestVersion()
            if (latest != null && latest.version_code > BuildConfig.VERSION_CODE) {
                _state.value = AppUpdateState.UpdateAvailable(latest)
            } else {
                _state.value = AppUpdateState.Idle
                if (isManual && context != null) {
                    val message = if (latest != null) {
                        "Bạn đang ở phiên bản mới nhất (${BuildConfig.VERSION_NAME})"
                    } else {
                        "Không thể kiểm tra bản cập nhật"
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun dismissUpdate() {
        if (!startingDownload && downloadId == NO_DOWNLOAD) _state.value = AppUpdateState.Idle
    }

    fun downloadUpdate(update: AppVersionResponse) {
        if (startingDownload || downloadId != NO_DOWNLOAD || _state.value !is AppUpdateState.UpdateAvailable) return

        startingDownload = true
        _state.value = AppUpdateState.Downloading(null)
        viewModelScope.launch {
            try {
                downloadId = withContext(Dispatchers.IO) {
                    val request = DownloadManager.Request(Uri.parse(update.download_url))
                        .setTitle("Đang tải bản cập nhật")
                        .setDescription("Phiên bản ${update.version_name}")
                        .setMimeType("application/vnd.android.package-archive")
                        .setDestinationInExternalFilesDir(
                            getApplication<Application>(), Environment.DIRECTORY_DOWNLOADS,
                            "update_${update.version_code}_${UUID.randomUUID()}.apk"
                        )
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

                    // Save the system download ID before exposing the job to the UI.
                    val id = manager.enqueue(request)
                    if (!preferences.edit().putLong(KEY_DOWNLOAD_ID, id)
                            .putInt(KEY_VERSION, update.version_code).commit()
                    ) {
                        manager.remove(id)
                        error("Could not save download ID")
                    }
                    id
                }
                downloadVersion = update.version_code
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Could not enqueue update", error)
                _state.value = AppUpdateState.Error("Không thể bắt đầu tải bản cập nhật.")
            } finally {
                startingDownload = false
            }
        }
    }

    // Only the observer stops in the background. DownloadManager still owns the transfer.
    suspend fun observeDownloadWhileVisible() {
        if (downloadId != NO_DOWNLOAD && downloadVersion <= BuildConfig.VERSION_CODE) {
            val installedDownload = downloadId
            withContext(Dispatchers.IO) { manager.remove(installedDownload) }
            clearDownload()
            _state.value = AppUpdateState.Idle
            checkForUpdates()
        }
        while (true) {
            val id = downloadId
            if (id != NO_DOWNLOAD) {
                try {
                    val status = withContext(Dispatchers.IO) { readDownload(id) }
                    if (status is AppUpdateState.Error) clearDownload()
                    _state.value = status
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Could not read existing download", error)
                }
            }
            delay(500)
        }
    }

    private fun readDownload(id: Long): AppUpdateState {
        manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (cursor.moveToFirst()) {
                return when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val uri = manager.getUriForDownloadedFile(id)
                        if (uri != null) AppUpdateState.ReadyToInstall(uri)
                        else AppUpdateState.Error("Không tìm thấy tệp cài đặt.")
                    }
                    DownloadManager.STATUS_FAILED -> AppUpdateState.Error("Tải thất bại. Hãy kiểm tra cập nhật để thử lại.")
                    else -> {
                        val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        AppUpdateState.Downloading(if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null)
                    }
                }
            }
        }
        return AppUpdateState.Error("Lượt tải không còn trên thiết bị. Hãy kiểm tra cập nhật để thử lại.")
    }

    private suspend fun clearDownload() {
        withContext(Dispatchers.IO) {
            check(preferences.edit().remove(KEY_DOWNLOAD_ID).remove(KEY_VERSION).commit())
        }
        downloadId = NO_DOWNLOAD
        downloadVersion = 0
    }

    companion object {
        private const val TAG = "AppUpdateViewModel"
        private const val KEY_DOWNLOAD_ID = "download_id"
        private const val KEY_VERSION = "download_version"
        private const val NO_DOWNLOAD = -1L
    }
}
