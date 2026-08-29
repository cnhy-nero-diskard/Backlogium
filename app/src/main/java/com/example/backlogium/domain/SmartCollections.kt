package com.example.backlogium.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * A session shorter than this does not prove that a game was meaningfully played.
 *
 * The motivating case is a game relaunched for a few minutes after months away: that relaunch
 * must not rescue it from Dropped. The same floor keeps a loading-screen check or settings visit
 * from turning Never started into a played game.
 */
const val MEANINGFUL_SESSION_MINUTES = 15

/** The fixed derived collection kinds, in their presentation order. */
enum class SmartCollectionId {
    QUICK_WINS,
    NEVER_STARTED,
    ALMOST_DONE,
    DROPPED,
    COMPLETED,
}

/** Whether the achievement cache has enough information to make a completion decision. */
enum class AchievementDataState {
    NOT_FETCHED,
    NO_ACHIEVEMENTS,
    HAS_ACHIEVEMENTS,
}

/** Achievement facts supplied to the pure derivation. */
data class SmartCollectionAchievementSignals(
    val unlocked: Int = 0,
    val total: Int = 0,
    val state: AchievementDataState = AchievementDataState.NOT_FETCHED,
)

/** Session facts supplied to the pure derivation. */
data class MeaningfulSessionSignals(
    val meaningfulSessionCount: Int = 0,
    val lastMeaningfulSessionAt: Long? = null,
    /** Meaningful session minutes are needed for games without Steam-reported totals. */
    val meaningfulMinutes: Int = 0,
)

/** The library facts needed by [SmartCollections]. */
data class SmartCollectionGame(
    val appId: Long,
    val name: String,
    val playtimeMinutes: Int,
    val mainStoryMinutes: Int?,
)

/**
 * Playtime used by derived collections, with sub-threshold observed launches removed.
 *
 * Owned games' Steam total includes minutes from sessions the app observed. Subtracting the known
 * short-session portion is what makes a ten-minute relaunch remain Never started, while the raw
 * total still supplies historical playtime when no session explains it. Imported history and
 * qualifying sessions are retained as the two sources that remain meaningful to this feature.
 */
fun smartCollectionPlaytimeMinutes(
    source: GameSource,
    steamPlaytimeMinutes: Int,
    importedPlaytimeMinutes: Int,
    totalSessionMinutes: Int,
    meaningfulSessionMinutes: Int,
): Int {
    val shortSessionMinutes =
        (totalSessionMinutes - meaningfulSessionMinutes).coerceAtLeast(0)
    val ownedPlaytime =
        (steamPlaytimeMinutes - shortSessionMinutes).coerceAtLeast(0)
    return when (source) {
        GameSource.STEAM_OWNED ->
            maxOf(ownedPlaytime, importedPlaytimeMinutes + meaningfulSessionMinutes)
        GameSource.FAMILY_SHARED -> meaningfulSessionMinutes
    }.coerceAtLeast(0)
}

/** The evidence that placed a game in Completed. */
enum class CompletionBasis {
    ACHIEVEMENTS,
    PLAYTIME,
}

/** One immutable derived member. A null basis is expected outside Completed. */
data class SmartCollectionMember(
    val game: SmartCollectionGame,
    val completionBasis: CompletionBasis? = null,
)

/** All five memberships from one consistent snapshot of local facts. */
data class SmartCollectionResult(
    val membersByCollection: Map<SmartCollectionId, List<SmartCollectionMember>>,
) {
    operator fun get(id: SmartCollectionId): List<SmartCollectionMember> =
        membersByCollection[id].orEmpty()

    val quickWins: List<SmartCollectionMember> get() = this[SmartCollectionId.QUICK_WINS]
    val neverStarted: List<SmartCollectionMember> get() = this[SmartCollectionId.NEVER_STARTED]
    val almostDone: List<SmartCollectionMember> get() = this[SmartCollectionId.ALMOST_DONE]
    val dropped: List<SmartCollectionMember> get() = this[SmartCollectionId.DROPPED]
    val completed: List<SmartCollectionMember> get() = this[SmartCollectionId.COMPLETED]
}

/**
 * Pure derivation of the fixed, read-only collections shown by the Collections screen.
 *
 * There is deliberately no Room, Android, clock, or injection here. Callers provide the current
 * date and all local inputs, so a date boundary changes Dropped membership without a sync or a
 * persisted recompute.
 */
object SmartCollections {
    /** Dropped requires strictly more than two hours of recorded playtime. */
    const val DROPPED_MINIMUM_PLAYTIME_MINUTES = 2 * 60

    /** Dropped requires the last meaningful session to be more than thirty calendar days ago. */
    const val DROPPED_IDLE_DAYS = 30L

    /** Quick wins have a main story length of at most six hours. */
    const val QUICK_WIN_MAX_MAIN_STORY_MINUTES = 6 * 60

    /** Almost done starts at eighty percent of the main story length. */
    const val ALMOST_DONE_FRACTION = 0.8

    /**
     * Derive every list from one snapshot of library, session, achievement, and date facts.
     * [sessionZone] defaults to UTC so JVM callers have deterministic timestamp boundaries; the
     * app passes its local zone when rendering on a device.
     */
    fun derive(
        games: List<SmartCollectionGame>,
        sessionsByGame: Map<Long, MeaningfulSessionSignals>,
        achievementsByGame: Map<Long, SmartCollectionAchievementSignals>,
        today: LocalDate,
        sessionZone: ZoneId = ZoneOffset.UTC,
    ): SmartCollectionResult {
        val rows = games
            .distinctBy { it.appId }
            .sortedWith(compareBy<SmartCollectionGame> { it.name.lowercase() }.thenBy { it.appId })
            .map { game ->
                val achievements = achievementsByGame[game.appId]
                    ?: SmartCollectionAchievementSignals()
                val session = sessionsByGame[game.appId]
                    ?: MeaningfulSessionSignals()
                DerivedGame(game, achievements, session, completionBasis(game, achievements))
            }

        fun membersWhere(
            predicate: (DerivedGame) -> Boolean,
            discloseCompletionBasis: Boolean = false,
        ): List<SmartCollectionMember> = rows
            .filter(predicate)
            .map { row ->
                SmartCollectionMember(
                    game = row.game,
                    completionBasis = row.completionBasis.takeIf { discloseCompletionBasis },
                )
            }

        return SmartCollectionResult(
            membersByCollection = mapOf(
                SmartCollectionId.QUICK_WINS to membersWhere(
                    predicate = { row ->
                        row.game.playtimeMinutes == 0 &&
                            row.game.mainStoryMinutes?.let {
                                it > 0 && it <= QUICK_WIN_MAX_MAIN_STORY_MINUTES
                            } == true
                    },
                ),
                SmartCollectionId.NEVER_STARTED to membersWhere(
                    predicate = { row ->
                        row.game.playtimeMinutes == 0
                    },
                ),
                SmartCollectionId.ALMOST_DONE to membersWhere(
                    predicate = { row ->
                        !row.isCompleted &&
                            row.game.mainStoryMinutes?.let { mainStory ->
                                mainStory > 0 &&
                                    row.game.playtimeMinutes.toDouble() / mainStory >= ALMOST_DONE_FRACTION
                            } == true
                    },
                ),
                SmartCollectionId.DROPPED to membersWhere(
                    predicate = { row ->
                        !row.isCompleted &&
                            row.game.playtimeMinutes > DROPPED_MINIMUM_PLAYTIME_MINUTES &&
                            row.session.meaningfulSessionCount > 0 &&
                            row.session.lastMeaningfulSessionAt
                                ?.toLocalDate(sessionZone)
                                ?.let { lastPlayed ->
                                    ChronoUnit.DAYS.between(lastPlayed, today) > DROPPED_IDLE_DAYS
                                } == true
                    },
                ),
                SmartCollectionId.COMPLETED to membersWhere(
                    predicate = { row -> row.isCompleted },
                    discloseCompletionBasis = true,
                ),
            ),
        )
    }

    private data class DerivedGame(
        val game: SmartCollectionGame,
        val achievements: SmartCollectionAchievementSignals,
        val session: MeaningfulSessionSignals,
        val completionBasis: CompletionBasis?,
    ) {
        val isCompleted: Boolean get() = completionBasis != null
    }

    private fun completionBasis(
        game: SmartCollectionGame,
        achievements: SmartCollectionAchievementSignals,
    ): CompletionBasis? = when (achievements.state) {
        AchievementDataState.NOT_FETCHED -> null
        AchievementDataState.HAS_ACHIEVEMENTS ->
            (achievements.total > 0 && achievements.unlocked >= achievements.total)
                .takeIf { it }
                ?.let { CompletionBasis.ACHIEVEMENTS }
        AchievementDataState.NO_ACHIEVEMENTS ->
            (game.mainStoryMinutes != null &&
                game.mainStoryMinutes > 0 &&
                game.playtimeMinutes >= game.mainStoryMinutes)
                .takeIf { it }
                ?.let { CompletionBasis.PLAYTIME }
    }
}

private fun Long.toLocalDate(zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
