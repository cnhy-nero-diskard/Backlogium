package com.example.backlogium.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.BuildConfig
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.repo.LiveStatusRepository
import com.example.backlogium.data.repo.NowPlaying
import com.example.backlogium.data.repo.ProfileRepository
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.gamification.Gamification
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val loading: Boolean = true,
    val configured: Boolean = true,
    val level: Int = 1,
    val xpIntoLevel: Int = 0,
    val xpForNext: Int = 0,
    val totalXp: Int = 0,
    val questMet: Boolean = false,
    val todayMinutes: Int = 0,
    val questThreshold: Int = 30,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val lastSyncAt: Long = 0L,
    val lastSyncError: String? = null,
    val isSyncing: Boolean = false,
    val isInGame: Boolean = false,
    val nowPlayingName: String? = null,
    val nowPlayingIconUrl: String? = null,
    /** True once historical Steam playtime has been imported (one-time). */
    val historyImported: Boolean = false,
    /** True while the one-time history import runs. */
    val isImportingHistory: Boolean = false,
) {
    val xpFraction: Float
        get() = if (xpForNext > 0) (xpIntoLevel.toFloat() / xpForNext).coerceIn(0f, 1f) else 0f
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val liveStatusRepository: LiveStatusRepository,
    private val settings: SettingsDataStore,
    private val time: TimeProvider,
) : ViewModel() {

    private val baseState: Flow<HomeUiState> = combine(
        profileRepository.profile,
        profileRepository.dailyProgress,
        settings.ruleConfigFlow,
        settings.steamIdFlow,
        profileRepository.syncInProgress,
    ) { profile, days, config, steamId, isSyncing ->
        val todayKey = time.today().toString()
        val todayProgress = days.firstOrNull { it.date == todayKey }
        val xpState = Gamification.levelState(profile?.totalXp ?: 0, config)
        HomeUiState(
            loading = false,
            configured = BuildConfig.STEAM_API_KEY.isNotBlank() && steamId.isNotBlank(),
            level = xpState.level,
            xpIntoLevel = xpState.xpIntoLevel,
            xpForNext = xpState.xpForNext,
            totalXp = xpState.totalXp,
            questMet = todayProgress?.questMet ?: false,
            todayMinutes = todayProgress?.minutesPlayed ?: 0,
            questThreshold = config.questThresholdMin,
            currentStreak = profile?.currentStreak ?: 0,
            longestStreak = profile?.longestStreak ?: 0,
            lastSyncAt = profile?.lastSyncAt ?: 0L,
            lastSyncError = profile?.lastSyncError,
            isSyncing = isSyncing,
            historyImported = profile?.playtimeBackfilled ?: false,
        )
    }

    // Local, view-scoped flag for the in-flight import (the persisted result lands via the
    // profile flow); kept out of baseState so the button can show progress immediately.
    private val isImportingHistory = MutableStateFlow(false)

    // Folding the live poll in here (rather than a separate collector) makes the 30s poll
    // observation-scoped: WhileSubscribed keeps LiveStatusRepository.nowPlaying collected only
    // while Home is observed, so polling starts with the screen and stops shortly after.
    val uiState: StateFlow<HomeUiState> = combine(
        baseState,
        liveStatusRepository.nowPlaying,
        isImportingHistory,
    ) { state, nowPlaying, importing ->
        val withImport = state.copy(isImportingHistory = importing)
        when (nowPlaying) {
            is NowPlaying.InGame -> withImport.copy(
                isInGame = true,
                nowPlayingName = nowPlaying.name,
                nowPlayingIconUrl = nowPlaying.iconUrl,
            )
            NowPlaying.NotPlaying -> withImport
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    fun syncNow() = profileRepository.syncNow()

    /**
     * Run the one-time historical-playtime import. Idempotent in the use-case; the resulting
     * XP/level change flows back through the observed profile. Guards against concurrent taps.
     */
    fun importSteamHistory() = runHistoryOp { profileRepository.importSteamHistory() }

    /**
     * Undo a prior import so it can be run again (recovery / opt-out). Clears the frozen
     * offsets and flag; the XP/level change flows back through the observed profile.
     */
    fun resetHistoryImport() = runHistoryOp { profileRepository.resetSteamHistoryImport() }

    // Serialize import/reset behind one in-flight flag so the buttons show progress and
    // concurrent taps can't overlap.
    private fun runHistoryOp(op: suspend () -> Unit) {
        if (isImportingHistory.value) return
        viewModelScope.launch {
            isImportingHistory.update { true }
            try {
                op()
            } finally {
                isImportingHistory.update { false }
            }
        }
    }
}
