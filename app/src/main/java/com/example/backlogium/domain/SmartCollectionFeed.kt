package com.example.backlogium.domain

import com.example.backlogium.data.repo.AchievementRepository
import com.example.backlogium.data.repo.GameRepository
import com.example.backlogium.data.repo.LibraryGame
import com.example.backlogium.data.repo.SessionRepository
import com.example.backlogium.data.repo.GamePreferenceRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** One derivation pass, with the inputs the presenting surfaces still need alongside it. */
data class SmartCollectionSnapshot(
    val games: List<LibraryGame>,
    val achievementsByGame: Map<Long, SmartCollectionAchievementSignals>,
    val sessionCountByGame: Map<Long, Int>,
    val result: SmartCollectionResult,
)

/**
 * The single source of derived-collection membership.
 *
 * Home and the Collections screen both present these lists, and a rule that meant one thing in a
 * Home card and another in the list it opens would be worse than either surface alone. Deriving
 * once, here, is what makes them the same lists rather than two implementations of the same idea.
 */
@Singleton
class SmartCollectionFeed @Inject constructor(
    gameRepository: GameRepository,
    achievementRepository: AchievementRepository,
    sessionRepository: SessionRepository,
    private val currentDate: CurrentDateProvider,
    preferences: GamePreferenceRepository,
) {
    private data class SessionFacts(
        val trackedMinutesByGame: Map<Long, Int>,
        val latestSessionAtByGame: Map<Long, Long>,
        val sessionCountByGame: Map<Long, Int>,
        val latestMeaningfulSessionAtByGame: Map<Long, Long>,
    )

    private val sessionFacts: Flow<SessionFacts> = combine(
        sessionRepository.trackedMinutesByGame,
        sessionRepository.latestSessionAtByGame,
        sessionRepository.sessionCountByGame,
        sessionRepository.latestMeaningfulSessionAtByGame,
    ) { trackedMinutes, latestSessionAt, sessionCounts, meaningful ->
        SessionFacts(trackedMinutes, latestSessionAt, sessionCounts, meaningful)
    }

    val snapshot: Flow<SmartCollectionSnapshot> = combine(
        gameRepository.library,
        sessionFacts,
        achievementRepository.smartCollectionSignals,
        currentDate.currentMoment,
        preferences.favoriteAppIds,
    ) { games, sessions, achievementsByGame, moment, favorites ->
        SmartCollectionSnapshot(
            games = games,
            achievementsByGame = achievementsByGame,
            sessionCountByGame = sessions.sessionCountByGame,
            result = SmartCollections.derive(
                games = games.map { game ->
                    SmartCollectionGame(
                        appId = game.appId,
                        name = game.name,
                        // Exactly one of the two is ever nonzero: backfillMinutes only an owned
                        // game's history import can set, manualSharedMinutes only a family-shared
                        // game's own estimate. Summing both is equivalent to a per-source read and
                        // avoids a source branch here (add-shared-game-playtime-and-filter).
                        playtimeMinutes = smartCollectionPlaytimeMinutes(
                            source = game.source,
                            steamPlaytimeMinutes = game.playtimeForever,
                            importedPlaytimeMinutes = (game.backfillMinutes.toLong() +
                                game.manualSharedMinutes.toLong())
                                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                            sessionMinutes = sessions.trackedMinutesByGame[game.appId] ?: 0,
                        ),
                        mainStoryMinutes = game.mainStoryMinutes,
                        // Steam's stamp covers the years before this app watched anything; a
                        // tracked session covers a family-shared game Steam reports nothing for.
                        // The later of the two is the last time the game was actually played.
                        lastPlayedAt = maxOfNotNull(
                            game.lastPlayedAt,
                            sessions.latestSessionAtByGame[game.appId],
                        ),
                        recentPlayedAt = maxOfNotNull(game.lastPlayedAt,
                            sessions.latestMeaningfulSessionAtByGame[game.appId]),
                    )
                },
                achievementsByGame = achievementsByGame,
                today = moment.today,
                sessionZone = moment.zone,
                nowMillis = moment.nowMillis,
                favoriteAppIds = favorites,
            ),
        )
    }
}

private fun maxOfNotNull(first: Long?, second: Long?): Long? = when {
    first == null -> second
    second == null -> first
    else -> maxOf(first, second)
}
