package com.nndai.myhome.presentation.device.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nndai.myhome.data.model.ActiveInvite
import com.nndai.myhome.data.model.CreatedInvite
import com.nndai.myhome.data.model.DeviceMember
import com.nndai.myhome.data.repository.DeviceShareRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State + actions for MemberManageScreen: member list, active invites and
 * all share-management operations. Errors surface through [message] (one
 * shot, consumed by the UI as snackbar).
 */
class MemberManageViewModel(
    private val deviceUuid: String,
    private val repository: DeviceShareRepository = DeviceShareRepository()
) : ViewModel() {

    private val _members = MutableStateFlow<List<DeviceMember>>(emptyList())
    val members = _members.asStateFlow()

    private val _invites = MutableStateFlow<List<ActiveInvite>>(emptyList())
    val invites = _invites.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy = _isBusy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    /** Invite vừa tạo — hiển thị cho user copy/share cho tới khi đóng. */
    private val _createdInvite = MutableStateFlow<CreatedInvite?>(null)
    val createdInvite = _createdInvite.asStateFlow()

    fun consumeMessage() { _message.value = null }

    fun dismissCreatedInvite() { _createdInvite.value = null }

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            val membersR = repository.listMembers(deviceUuid)
            val invitesR = repository.listInvites(deviceUuid)
            membersR.onSuccess { _members.value = it }
                .onFailure { _message.value = it.message }
            invitesR.onSuccess { _invites.value = it }
                .onFailure { it.message?.let { msg -> _message.value = msg } }
            _isLoading.value = false
        }
    }

    fun createInvite(role: String) {
        viewModelScope.launch {
            _isBusy.value = true
            repository.createInvite(deviceUuid, role)
                .onSuccess {
                    _createdInvite.value = it
                    refreshInvitesOnly()
                }
                .onFailure { _message.value = it.message }
            _isBusy.value = false
        }
    }

    fun revokeInvite(code: String) {
        viewModelScope.launch {
            _isBusy.value = true
            repository.revokeInvite(code)
                .onSuccess {
                    _message.value = "Đã thu hồi mã chia sẻ"
                    refreshInvitesOnly()
                }
                .onFailure { _message.value = it.message }
            _isBusy.value = false
        }
    }

    fun updateMemberRole(userId: String, newRole: String) {
        viewModelScope.launch {
            _isBusy.value = true
            repository.updateMemberRole(deviceUuid, userId, newRole)
                .onSuccess {
                    _message.value = "Đã cập nhật vai trò"
                    refresh()
                }
                .onFailure { _message.value = it.message }
            _isBusy.value = false
        }
    }

    fun removeMember(userId: String) {
        viewModelScope.launch {
            _isBusy.value = true
            repository.removeMember(deviceUuid, userId)
                .onSuccess {
                    _message.value = "Đã xóa thành viên"
                    refresh()
                }
                .onFailure { _message.value = it.message }
            _isBusy.value = false
        }
    }

    private suspend fun refreshInvitesOnly() {
        repository.listInvites(deviceUuid).onSuccess { _invites.value = it }
    }
}
