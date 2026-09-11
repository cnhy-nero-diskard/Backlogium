package com.example.backlogium.domain.gapplan

import com.example.backlogium.data.repo.GameCategory
import com.example.backlogium.data.repo.GameGenreRepository
import com.example.backlogium.data.repo.GameRepository
import com.example.backlogium.data.repo.GameReviewSummary
import com.example.backlogium.data.repo.LibraryGame
import com.example.backlogium.data.repo.PersonalPaceRepository
import com.example.backlogium.data.repo.SessionRepository
import com.example.backlogium.data.repo.SteamReviewRepository
import com.example.backlogium.data.repo.advertisesMultiplayer
import com.example.backlogium.domain.CurrentDateProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place gap-plan inputs are assembled.
 *
 * Follows `SmartCollectionFeed`: one shared derivation rather than a screen-local one, so Home and
 * Collections cannot produce different answers from the same facts. A Compose-local implementation
 * was rejected for the same reason, plus it would couple the engine's behaviour to UI state and
 * make exhaustive testing impractical.
 *
 * The feed reads **two distinct session aggregates and must not conflate them**:
 * [SessionRepository.trackedMinutesByGame] is all-time, and is what makes a family-shared game's
 * playtime truthful; `closedSessionsSince` is a bounded window of dated rows, and is what genre
 * affinity is derived from. Using the windowed figure for playtime would understate every shared
 * game's progress and re-plan work already done.
 */
@Singleton
class GapPlanFeed @Inject constructor(
    private val gameRepository: GameRepository,
    private val sessionRepository: SessionRepository,
    private val paceRepository: PersonalPaceRepository,
    private val genreRepository: GameGenreRepository,
    private val reviewRepository: SteamReviewRepository,
    private val currentDate: CurrentDateProvider,
) {
    private data class Metadata(
        val categoriesByApp: Map<Long, List<GameCategory>?>,
        val reviewsByApp: Map<Long, GameReviewSummary>,
    ) {
        /**
         * True only when the cache positively says so. A game with no row, or a row whose category
         * payload was never retrieved, is **not** multiplayer here — which costs nothing, because
         * the flag gates an optional decoration and never membership. Reading unknown as
         * single-player would be wrong in a surface that acted on it; reading it as multiplayer
         * would spend live lookups on games that cannot use them.
         */
        fun multiplayer(appId: Long): Boolean =
            categoriesByApp[appId]?.advertisesMultiplayer() == true
    }

    private val metadata: Flow<Metadata> = combine(
        genreRepository.allCategories,
        reviewRepository.allReviews,
    ) { categories, reviews -> Metadata(categories, reviews) }

    /**
     * One consistent snapshot of everything a generation reads.
     *
     * The date comes from [CurrentDateProvider], which re-emits on a local day boundary, and the
     * session window is recomputed from it on every emission. `PersonalPaceRepository` is a
     * `@Singleton` that captures its own `today` at construction, so a long-lived process that
     * crossed midnight would otherwise plan from a stale "tomorrow" — the profile is taken from
     * that repository, but the planning window is derived here, from a freshly read date.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val inputs: Flow<GapPlanInputs> = currentDate.currentDate.flatMapLatest { today ->
        val affinityCutoff = today
            .minusDays(GenreAffinity.LOOKBACK_DAYS)
            .atStartOfDay(currentDate.zone)
            .toInstant()
            .toEpochMilli()

        combine(
            gameRepository.library,
            sessionRepository.trackedMinutesByGame,
            sessionRepository.closedSessionsSince(affinityCutoff),
            paceRepository.profile,
            metadata,
        ) { library, trackedMinutes, windowedSessions, profile, meta ->
            GapPlanInputs(
                games = library.map { game -> game.toGapPlanGame(trackedMinutes, meta) },
                playedDates = windowedSessions.map { session ->
                    PlayedDate(
                        appId = session.appId,
                        date = Instant.ofEpochMilli(session.startAt)
                            .atZone(currentDate.zone)
                            .toLocalDate(),
                    )
                },
                reviewsByAppId = meta.reviewsByApp,
                // Labels come from the library join the app already performs, so a genre reason
                // names the same words the rest of the app shows for that genre.
                genreLabels = library
                    .flatMap { it.genres }
                    .associate { it.id to it.label },
                paceProfile = profile,
                today = today,
            )
        }
    }

    /**
     * A joined library row to the engine's plain projection.
     *
     * [LibraryGame] is already a domain model, so nothing storage-shaped crosses here. The three
     * playtime figures are kept separate rather than pre-summed: only the source-aware rule inside
     * the engine knows which of them is truthful for a given game.
     */
    private fun LibraryGame.toGapPlanGame(
        trackedMinutesByGame: Map<Long, Int>,
        metadata: Metadata,
    ): GapPlanGame = GapPlanGame(
        appId = appId,
        name = name,
        source = source,
        steamPlaytimeMinutes = playtimeForever,
        trackedMinutes = trackedMinutesByGame[appId] ?: 0,
        manualSharedMinutes = manualSharedMinutes,
        mainStoryMinutes = mainStoryMinutes,
        completionistMinutes = completionistMinutes,
        genreIds = genres.map { it.id },
        multiplayer = metadata.multiplayer(appId),
    )
}
