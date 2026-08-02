package com.example.backlogium.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.repo.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Remembers whether the runtime notification permission has already been put to the user, so the
 * shell asks exactly once rather than on every launch (see
 * [com.example.backlogium.data.local.SettingsDataStore.notificationPermissionRequestedFlow]).
 *
 * Null while the answer is still being read: the shell must not fire the request on a default that
 * later turns out to be wrong, so "unknown" is deliberately distinct from "not yet asked".
 */
@HiltViewModel
class NotificationPermissionViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    val alreadyRequested: StateFlow<Boolean?> = settings.notificationPermissionRequested
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    fun markRequested() {
        viewModelScope.launch { settings.setNotificationPermissionRequested() }
    }
}
