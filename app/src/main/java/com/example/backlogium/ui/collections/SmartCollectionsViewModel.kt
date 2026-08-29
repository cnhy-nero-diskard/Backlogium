package com.example.backlogium.ui.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.repo.CollectionRepository
import com.example.backlogium.data.repo.LibraryGame
import com.example.backlogium.data.repo.LiveStatusRepository
import com.example.backlogium.data.repo.NowPlaying
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.domain.AchievementDataState
import com.example.backlogium.domain.CompletionBasis
import com.example.backlogium.domain.GameListDensity
import com.example.backlogium.domain.GameSource
import com.example.backlogium.domain.SmartCollectionAchievementSignals
import com.example.backlogium.domain.SmartCollectionFeed
import com.example.backlogium.domain.SmartCollectionId
import com.example.backlogium.domain.SmartCollectionMember
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One custom collection card shown on the Collections destination. */
data class CustomCollectionCardUi(
    val id: Long,
    val name: String,
    val memberCount: Int,
    val description: String?,
)

/** One game shown inside a read-only derived collection. */
data class SmartCollectionMemberUi(
    val appId: Long,
    val name: String,
    val iconUrl: String,
    val headerUrl: String,
    val heroCapsuleUrl: String,
    val playtimeMinutes: Int,
    val achievementsUnlocked: Int? = null,
    val achievementsTotal: Int? = null,
    val sessionCount: Int = 0,
    val completionistMinutes: Int? = null,
    val mainStoryMinutes: Int? = null,
    val mainExtraMinutes: Int? = null,
    val allStylesMinutes: Int? = null,
    val completionBasis: CompletionBasis? = null,
    /** Family-shared playtime is observed by Backlogium rather than a Steam lifetime total. */
    val isFamilyShared: Boolean = false,
    val isCurrentlyPlaying: Boolean = false,
)

/** A derived collection card, including hidden lists so the manage dialog can restore them. */
data class SmartCollectionCardUi(
    val id: SmartCollectionId,
    val name: String,
    val rule: String,
    val members: List<SmartCollectionMemberUi>,
    val hidden: Boolean,
)

data class CollectionsUiState(
    val loading: Boolean = true,
    val customCollections: List<CustomCollectionCardUi> = emptyList(),
    val smartCollections: List<SmartCollectionCardUi> = emptyList(),
    val collectionDensity: GameListDensity = GameListDensity.LIST,
) {
    /** Empty derived lists are omitted even when their visibility setting is on. */
    val visibleSmartCollections: List<SmartCollectionCardUi>
        get() = smartCollections.filter { !it.hidden && it.members.isNotEmpty() }
}

/** Fixed names are kept beside the derivation so the list and its detail view cannot diverge. */
internal fun smartCollectionName(id: SmartCollectionId): String = when (id) {
    SmartCollectionId.QUICK_WINS -> "Quick wins"
    SmartCollectionId.NEVER_STARTED -> "Never started"
    SmartCollectionId.ALMOST_DONE -> "Almost done"
    SmartCollectionId.DROPPED -> "Dropped"
    SmartCollectionId.COMPLETED -> "Completed"
}

/** The rule is visible at the point where each derived list is presented. */
internal fun smartCollectionRule(id: SmartCollectionId): String = when (id) {
    SmartCollectionId.QUICK_WINS ->
        "Never started, with a Main Story length of at most 6 hours."
    SmartCollectionId.NEVER_STARTED ->
        "No recorded playtime from Steam, imported history, or tracked sessions."
    SmartCollectionId.ALMOST_DONE ->
        "At least 80% of Main Story and at least 80% of achievements unlocked, but not completed."
    SmartCollectionId.DROPPED ->
        "More than 1.5 hours played, not completed, and not played in over 30 days."
    SmartCollectionId.COMPLETED ->
        "All achievements unlocked, or no achievements and playtime at least the Main Story length."
}

internal const val SMART_COLLECTION_MISSING_HLTB_NOTE =
    "Lists that use Main Story length exclude games without a HowLongToBeat length, and Almost " +
        "done excludes games whose achievements have not been fetched yet."

@HiltViewModel
class SmartCollectionsViewModel @Inject constructor(
    private val collectionRepository: CollectionRepository,
    private val smartCollectionFeed: SmartCollectionFeed,
    private val settings: SettingsRepository,
    private val liveStatusRepository: LiveStatusRepository,
) : ViewModel() {

    private val smartCards: Flow<List<SmartCollectionCardUi>> = combine(
        smartCollectionFeed.snapshot,
        settings.smartCollectionVisibility,
        liveStatusRepository.nowPlaying,
    ) { snapshot, visibility, nowPlaying ->
        val playingAppId = (nowPlaying as? NowPlaying.InGame)?.gameId
        SmartCollectionId.entries.map { id ->
            SmartCollectionCardUi(
                id = id,
                name = smartCollectionName(id),
                rule = smartCollectionRule(id),
                members = snapshot.result[id].map { member ->
                    member.toUi(
                        games = snapshot.games,
                        achievementsByGame = snapshot.achievementsByGame,
                        sessionCountByGame = snapshot.sessionCountByGame,
                        playingAppId = playingAppId,
                    )
                },
                hidden = !visibility.isVisible(id),
            )
        }
    }

    val uiState: StateFlow<CollectionsUiState> = combine(
        collectionRepository.customOverviews,
        smartCards,
        settings.collectionDensity,
    ) { custom, smart, density ->
        CollectionsUiState(
            loading = false,
            customCollections = custom.map { overview ->
                CustomCollectionCardUi(
                    id = overview.id,
                    name = overview.name,
                    memberCount = overview.memberAppIds.size,
                    description = overview.description,
                )
            },
            smartCollections = smart,
            collectionDensity = density,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        CollectionsUiState(),
    )

    fun setDensity(density: GameListDensity) {
        viewModelScope.launch {
            settings.setCollectionDensity(density)
        }
    }

    fun setSmartCollectionVisible(id: SmartCollectionId, visible: Boolean) {
        viewModelScope.launch {
            settings.setSmartCollectionVisible(id, visible)
        }
    }
}

private fun SmartCollectionMember.toUi(
    games: List<LibraryGame>,
    achievementsByGame: Map<Long, SmartCollectionAchievementSignals>,
    sessionCountByGame: Map<Long, Int>,
    playingAppId: Long?,
): SmartCollectionMemberUi {
    val libraryGame = games.firstOrNull { it.appId == game.appId }
    val achievements = achievementsByGame[game.appId]
    return SmartCollectionMemberUi(
        appId = game.appId,
        name = game.name,
        iconUrl = libraryGame?.iconUrl.orEmpty(),
        headerUrl = libraryGame?.headerUrl.orEmpty(),
        heroCapsuleUrl = libraryGame?.heroCapsuleUrl.orEmpty(),
        playtimeMinutes = game.playtimeMinutes,
        achievementsUnlocked = achievements
            ?.takeIf { it.state == AchievementDataState.HAS_ACHIEVEMENTS }
            ?.unlocked,
        achievementsTotal = achievements
            ?.takeIf { it.state == AchievementDataState.HAS_ACHIEVEMENTS }
            ?.total,
        sessionCount = sessionCountByGame[game.appId] ?: 0,
        completionistMinutes = libraryGame?.completionistMinutes,
        mainStoryMinutes = game.mainStoryMinutes,
        mainExtraMinutes = libraryGame?.mainExtraMinutes,
        allStylesMinutes = libraryGame?.allStylesMinutes,
        completionBasis = completionBasis,
        isFamilyShared = libraryGame?.source == GameSource.FAMILY_SHARED,
        isCurrentlyPlaying = game.appId == playingAppId,
    )
}
