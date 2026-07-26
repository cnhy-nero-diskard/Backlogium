package com.example.backlogium.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.repo.CredentialsRepository
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.LivePresence
import com.example.backlogium.data.repo.LiveStatusRepository
import com.example.backlogium.data.repo.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Shell-scoped state for the profile header. Identity comes from Room (so a cold offline launch
 * is already populated); presence comes from the live poll and is never persisted.
 */
data class ProfileHeaderUiState(
    /** True until credentials have been read, so the header can avoid a flash of empty chrome. */
    val loading: Boolean = true,
    val configured: Boolean = false,
    val personaName: String? = null,
    val avatarUrl: String? = null,
    val presence: LivePresence = LivePresence.UNKNOWN,
) {
    /** True when the header should be rendered at all. */
    val visible: Boolean get() = !loading && configured
}

@HiltViewModel
class ProfileHeaderViewModel @Inject constructor(
    profileRepository: ProfileRepository,
    liveStatusRepository: LiveStatusRepository,
    credentials: CredentialsRepository,
) : ViewModel() {

    // WhileSubscribed keeps the shared live poll ticking only while the shell is on screen; the
    // poll is shared, so observing it here costs no extra request alongside Home.
    val uiState: StateFlow<ProfileHeaderUiState> = combine(
        profileRepository.profile,
        credentials.credentialsStateFlow,
        liveStatusRepository.liveStatus,
    ) { profile, credState, live ->
        ProfileHeaderUiState(
            loading = false,
            configured = credState is CredentialsState.Configured,
            personaName = profile?.personaName,
            avatarUrl = profile?.avatarUrl,
            presence = live.presence,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ProfileHeaderUiState(),
    )
}
