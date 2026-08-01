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
    /**
     * True while a Steam poll is in flight — periodic as well as manual. Already latched to a
     * perceptible minimum upstream, so the header can render it directly.
     */
    val syncing: Boolean = false,
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

    // A plain observer: LiveStatusRepository's poll is now owned by PresenceService
    // (enhance-now-playing), so collecting liveStatus here never starts or extends polling —
    // this just reflects whatever the service (or Home's start-on-open check) last found.
    val uiState: StateFlow<ProfileHeaderUiState> = combine(
        profileRepository.profile,
        credentials.credentialsStateFlow,
        liveStatusRepository.liveStatus,
        profileRepository.syncInProgress,
    ) { profile, credState, live, syncing ->
        ProfileHeaderUiState(
            loading = false,
            configured = credState is CredentialsState.Configured,
            personaName = profile?.personaName,
            avatarUrl = profile?.avatarUrl,
            presence = live.presence,
            syncing = syncing,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ProfileHeaderUiState(),
    )
}
