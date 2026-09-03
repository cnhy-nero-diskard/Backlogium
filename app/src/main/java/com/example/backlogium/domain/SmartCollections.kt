package com.example.backlogium.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

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

/** The library facts needed by [SmartCollections]. */
data class SmartCollectionGame(
    val appId: Long,
    val name: String,
    val playtimeMinutes: Int,
    val mainStoryMinutes: Int?,
    /**
     * When this game was last played, from whichever source knows — Steam's own last-played stamp
     * or an observed session, whichever is later — or null when neither does. Steam's stamp is what
     * lets a game Backlogium never watched still be recognised as abandoned.
     */
    val lastPlayedAt: Long? = null,
)

/**
 * Playtime used by derived collections.
 *
 * An owned game's Steam total is authoritative and already includes everything the app observed;
 * imported history plus tracked sessions is the fallback for whatever that total predates. A
 * family-shared game has no Steam total at all, so only what Backlogium watched exists.
 */
fun smartCollectionPlaytimeMinutes(
    source: GameSource,
    steamPlaytimeMinutes: Int,
    importedPlaytimeMinutes: Int,
    sessionMinutes: Int,
): Int = when (source) {
    GameSource.STEAM_OWNED ->
        maxOf(steamPlaytimeMinutes, importedPlaytimeMinutes + sessionMinutes)
    // importedPlaytimeMinutes carries a family-shared game's manual estimate here (its
    // backfillMinutes is always 0 -- only an owned game's history import can set that), additive
    // with tracked session minutes the same way an owned game's history import is
    // (add-shared-game-playtime-and-filter).
    GameSource.FAMILY_SHARED -> sessionMinutes + importedPlaytimeMinutes
}.coerceAtLeast(0)

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
 * Pure derivation of the fixed, read-only collections shown by the Collections screen and Home.
 *
 * There is deliberately no Room, Android, clock, or injection here. Callers provide the current
 * date and all local inputs, so a date boundary changes Dropped membership without a sync or a
 * persisted recompute.
 */
object SmartCollections {
    /** Dropped requires strictly more than an hour and a half of recorded playtime. */
    const val DROPPED_MINIMUM_PLAYTIME_MINUTES = 90

    /** Dropped requires the last play to be more than thirty calendar days ago. */
    const val DROPPED_IDLE_DAYS = 30L

    /** Quick wins have a main story length of at most six hours. */
    const val QUICK_WIN_MAX_MAIN_STORY_MINUTES = 6 * 60

    /** Almost done starts at eighty percent of the main story length. */
    const val ALMOST_DONE_FRACTION = 0.8

    /**
     * Almost done also requires eighty percent of achievements, where a game has any.
     *
     * Playtime alone reads as nearly finished a game the player is nowhere near done with: forty
     * hours into a roguelike, past its main-story length, with under half its achievements
     * unlocked, is not almost done by any reading a player would recognise. Achievements are
     * evidence of what was accomplished, so they gate the list rather than merely decorate it.
     */
    const val ALMOST_DONE_ACHIEVEMENT_FRACTION = 0.8

    /**
     * Derive every list from one snapshot of library, achievement, and date facts.
     * [sessionZone] defaults to UTC so JVM callers have deterministic timestamp boundaries; the
     * app passes its local zone when rendering on a device.
     */
    fun derive(
        games: List<SmartCollectionGame>,
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
                DerivedGame(game, achievements, completionBasis(game, achievements))
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
                            row.achievementsNearlyComplete &&
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
                            row.game.lastPlayedAt
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
        val completionBasis: CompletionBasis?,
    ) {
        val isCompleted: Boolean get() = completionBasis != null

        /**
         * Whether achievements permit an almost-done reading. A game confirmed to have none is
         * judged on playtime alone; one whose achievements have never been fetched is excluded,
         * because an unmet condition and an unknown one must not look the same.
         */
        val achievementsNearlyComplete: Boolean
            get() = when (achievements.state) {
                AchievementDataState.NOT_FETCHED -> false
                AchievementDataState.NO_ACHIEVEMENTS -> true
                AchievementDataState.HAS_ACHIEVEMENTS ->
                    achievements.total > 0 &&
                        achievements.unlocked.toDouble() / achievements.total >=
                        ALMOST_DONE_ACHIEVEMENT_FRACTION
            }
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
