package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.HltbMatchStatus
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.remote.SteamIconMapper
import com.example.backlogium.domain.GameSource
import com.example.backlogium.domain.exactExpiryTicks
import com.example.backlogium.domain.GameRecencyState
import com.example.backlogium.domain.LibraryRecency
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Steam's success code for `GetNumberOfCurrentPlayers`; any other value means no count. */
private const val CURRENT_PLAYERS_SUCCESS = 1

/**
 * Outcome of matching a game to HowLongToBeat, as consumers above `data/` see it. Mirrors the
 * stored [HltbMatchStatus] so no consumer depends on the storage enum.
 */
enum class HltbMatchState {
    /** A single confident match was resolved automatically (or confirmed via review). */
    RESOLVED,

    /** Multiple/low-confidence candidates; awaits user selection in the review surface. */
    NEEDS_REVIEW,

    /** The search returned no entries; the game carries no completion lengths. */
    UNMATCHED,
}

/**
 * A library game as consumers see it: the Steam facts they render plus the resolved
 * HowLongToBeat state, joined here so no consumer has to read the HLTB cache itself.
 *
 * The four HLTB lengths are null until a match resolves; [hltbMatchState] is null when no
 * lookup has been stored for this game yet. All four are carried (not just the two the Library
 * itself renders) so the game detail screen can present the full set from this one join rather
 * than opening a second read path into the HLTB cache — see enhance-game-detail.
 *
 * [playtime2Weeks] and [backfillMinutes] exist on the `Game` entity and were previously dropped
 * here; they are carried through because the Library sorts by recent activity and derives each
 * game's XP contribution from the engine's own inputs (backfill + tracked minutes), neither of
 * which `playtimeForever` can stand in for.
 */
data class LibraryGame(
    val appId: Long,
    val name: String,
    val iconUrl: String,
    /** Store header art, derived from the appId — the Library's faint card backdrop. */
    val headerUrl: String = "",
    /** Steam's portrait hero capsule, derived from the appId for grid surfaces. */
    val heroCapsuleUrl: String = "",
    val playtimeForever: Int,
    /** Steam's rolling two-week playtime — the "recently played" ordering. */
    val playtime2Weeks: Int = 0,
    /** Frozen historical playtime from the opt-in Steam-history import; 0 when not imported. */
    val backfillMinutes: Int = 0,
    val completionistMinutes: Int? = null,
    val mainStoryMinutes: Int? = null,
    val mainExtraMinutes: Int? = null,
    val allStylesMinutes: Int? = null,
    val hltbMatchState: HltbMatchState? = null,
    /** Tagged as a "Focus" game — drives History's per-day Focus-minutes breakdown. */
    val isGoal: Boolean = false,
    /** Ordered Steam Store genres; empty while unknown, unavailable, or malformed in cache. */
    val genres: List<GameGenre> = emptyList(),
    /**
     * The one recency signal this game currently carries, or null — already derived, so no
     * consumer re-implements the precedence or the window (add-library-recency-signals).
     */
    val recencyState: GameRecencyState? = null,
    /**
     * Steam's last-played time in epoch millis, or null where Steam reported none. Null is
     * "unknown", never "never played" — that is [playtimeForever] being 0.
     */
    val lastPlayedAt: Long? = null,
    val source: GameSource = GameSource.STEAM_OWNED,
)

/** Read/write access to the game library, exposing domain models as observable [Flow]s. */
@Singleton
class GameRepository @Inject constructor(
    private val gameDao: GameDao,
    private val hltbRepository: HltbRepository,
    private val gameGenreRepository: GameGenreRepository,
    private val steamApi: SteamApi,
    private val sessionRepository: SessionRepository,
    private val time: TimeProvider,
) {
    val library: Flow<List<LibraryGame>> = gameDao.observeLibrary().withHltb()
    val goalGames: Flow<List<LibraryGame>> = gameDao.observeGoalGames().withHltb()
    val backlog: Flow<List<LibraryGame>> = gameDao.observeBacklog().withHltb()

    /**
     * The game's current Steam concurrent-player count, or `null` on any failure — network error,
     * a non-success `result` (an invalid or delisted app id), or a missing count in an otherwise
     * successful response. Never persisted: this is a live fact, fetched fresh by the caller each
     * time it's needed — the game detail screen polls it every 30 seconds while open — the same
     * posture [LiveStatusRepository] takes with the player's own presence.
     */
    suspend fun currentPlayerCount(appId: Long): Int? = runCatching {
        steamApi.getNumberOfCurrentPlayers(appId).response
    }.getOrNull()
        ?.takeIf { it.result == CURRENT_PLAYERS_SUCCESS }
        ?.playerCount

    /**
     * Mark a game as a goal. No user-entered target is required (restyle-fixes): the manual
     * minutes target is retired, so the dormant [Game.targetMinutes] column is left untouched.
     *
     * Tagging also triggers a cache-first HowLongToBeat fetch so the game's Main Story length
     * becomes available for goal progress. The fetch is best-effort — a lookup failure never
     * blocks the tag nor clears cached data.
     */
    suspend fun tagGoal(appId: Long) {
        gameDao.setGoalFlag(appId, isGoal = true)
        val game = gameDao.getById(appId) ?: return
        runCatching { hltbRepository.fetchForGame(appId, game.name) }
    }

    /** Remove a game's goal tag and clear its target. */
    suspend fun untagGoal(appId: Long) =
        gameDao.setGoal(appId, isGoal = false, targetMinutes = null)

    /**
     * Joins each game with its cached HLTB row and its derived recency state, and maps them into
     * [LibraryGame].
     *
     * A recency state expires by arithmetic and nothing writes to retire it, so the join watches the
     * nearest recorded deadline with one collector-scoped delay. This invalidates at the actual
     * expiry rather than waiting for local midnight; there is no worker, alarm, or per-row polling.
     *
     * The first-session times arrive as one grouped query for the whole library, so deriving a
     * state per row costs no query per row.
     */
    private fun Flow<List<Game>>.withHltb(): Flow<List<LibraryGame>> =
        combine(hltbRepository.allData) { games, hltb -> games to hltb }
            .combine(gameGenreRepository.allGenres) { (games, hltb), genres -> Triple(games, hltb, genres) }
            .combine(sessionRepository.firstSessionAtByGame) { (games, hltb, genres), firstSessions ->
                RecencyJoin(games, hltb, genres, firstSessions)
            }
            .flatMapLatest { join ->
                exactExpiryTicks(
                    nowMillis = time::nowMillis,
                    nextExpiryAt = { now -> nextRecencyExpiryAt(join, now) },
                ).map { now ->
                    val rowByAppId = join.hltb.associateBy(HltbData::appId)
                    join.games.map { game ->
                        game.toDomain(
                            hltb = rowByAppId[game.appId],
                            genres = join.genres[game.appId].orEmpty(),
                            recencyState = LibraryRecency.derive(
                                firstSeenAt = game.firstSeenAt,
                                returnedToPlayAt = game.returnedToPlayAt,
                                playtimeForever = game.playtimeForever,
                                firstSessionAt = join.firstSessionAtByGame[game.appId],
                                now = now,
                            ),
                        )
                    }
                }
            }

    /** The four library-wide inputs one pass of the join needs, gathered before any per-row work. */
    private fun nextRecencyExpiryAt(join: RecencyJoin, now: Long): Long? = join.games.asSequence()
        .flatMap { game ->
            sequenceOf(
                join.firstSessionAtByGame[game.appId],
                game.returnedToPlayAt,
                game.firstSeenAt.takeIf { game.playtimeForever == 0 },
            ).filterNotNull()
        }
        .map { it + LibraryRecency.BADGE_WINDOW_MILLIS }
        .filter { it > now }
        .minOrNull()

    private data class RecencyJoin(
        val games: List<Game>,
        val hltb: List<HltbData>,
        val genres: Map<Long, List<GameGenre>>,
        val firstSessionAtByGame: Map<Long, Long>,
    )
}

private fun Game.toDomain(
    hltb: HltbData?,
    genres: List<GameGenre>,
    recencyState: GameRecencyState?,
) = LibraryGame(
    appId = appId,
    name = name,
    iconUrl = iconUrl,
    headerUrl = SteamIconMapper.headerUrl(appId),
    heroCapsuleUrl = SteamIconMapper.heroCapsuleUrl(appId),
    playtimeForever = playtimeForever,
    playtime2Weeks = playtime2Weeks,
    backfillMinutes = backfillMinutes,
    completionistMinutes = hltb?.completionistMinutes,
    mainStoryMinutes = hltb?.mainStoryMinutes,
    mainExtraMinutes = hltb?.mainExtraMinutes,
    allStylesMinutes = hltb?.allStylesMinutes,
    hltbMatchState = hltb?.matchStatus?.toDomain(),
    isGoal = isGoal,
    genres = genres,
    recencyState = recencyState,
    lastPlayedAt = lastPlayedAt,
    source = source,
)

/** Storage → domain status mapping; internal so [HltbRepository] can report batch outcomes. */
internal fun HltbMatchStatus.toDomain() = when (this) {
    HltbMatchStatus.RESOLVED -> HltbMatchState.RESOLVED
    HltbMatchStatus.NEEDS_REVIEW -> HltbMatchState.NEEDS_REVIEW
    HltbMatchStatus.UNMATCHED -> HltbMatchState.UNMATCHED
}
