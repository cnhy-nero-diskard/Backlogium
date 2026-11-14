package com.example.backlogium.data.repo

import com.example.backlogium.data.achievement.achievementRetryAfter
import com.example.backlogium.data.achievement.achievementRetryDelay
import com.example.backlogium.data.achievement.AchievementNetwork
import com.example.backlogium.data.local.entity.AchievementCatalogueEntry
import com.example.backlogium.data.local.entity.AchievementCatalogueCache
import com.example.backlogium.domain.AchievementCatalogueState
import com.example.backlogium.domain.AchievementProgressAvailability
import com.example.backlogium.domain.AchievementHydrationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import com.example.backlogium.data.achievement.AchievementObservationStore
import com.example.backlogium.data.local.entity.AchievementWatchWork
import com.example.backlogium.domain.AchievementUnlockGroup
import com.example.backlogium.domain.AchievementUnlockItem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import com.example.backlogium.data.achievement.AchievementFreshness
import com.example.backlogium.data.achievement.AchievementMerge
import com.example.backlogium.data.local.dao.AchievementCounts
import com.example.backlogium.data.local.dao.AchievementDao
import com.example.backlogium.data.local.dao.AchievementRarity
import com.example.backlogium.data.local.dao.AchievementUnlock
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.GameAchievementSyncDao
import com.example.backlogium.data.diagnostics.SyncRunRecorder
import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.local.entity.GameAchievementSync
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.remote.dto.AchievementSchemaDto
import com.example.backlogium.data.remote.dto.PlayerAchievementDto
import com.example.backlogium.domain.AchievementDataState
import com.example.backlogium.domain.SmartCollectionAchievementSignals
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.domain.RecoveryAchievementSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One achievement as consumers see it. [rarityPercent] is the frozen rarity snapshot — the
 * global unlock percent captured the first sync that observed the achievement unlocked, never
 * the live percent — so tier/XP derived from it cannot drift (add-steam-achievements design).
 * Null when the achievement is still locked or no percent was ever captured.
 *
 * [globalPercent] is the live percent, refreshed every sync and present regardless of unlock
 * state. It is **not** an alternative input to tier/XP — those stay pinned to [rarityPercent] —
 * but it is the only rarity signal a *locked* achievement has, so display and sorting fall back
 * to it for those rows (enhance-game-detail). [unlockedAt] is null while locked.
 */
data class GameAchievement(
    val apiName: String,
    val displayName: String,
    val iconUrl: String?,
    val unlocked: Boolean,
    val rarityPercent: Double?,
    val globalPercent: Double? = null,
    val unlockedAt: Long? = null,
    val description: String? = null,
    val hidden: Boolean = false,
    val progressKnown: Boolean = true,
)

/** Unlocked and total counts for one game, as consumed by product surfaces. */
data class AchievementCountSummary(
    val appId: Long,
    val total: Int,
    val unlocked: Int,
)

/** Minimal achievement unlock facts needed for History thumbnails. */
data class AchievementUnlockSummary(
    val appId: Long,
    val iconUrl: String?,
    val unlockedAt: Long,
)

/** An unlocked achievement with the frozen percent used for its rarity tier and its game name. */
data class UnlockedAchievementRarity(
    val appId: Long,
    val gameName: String,
    val achievementName: String,
    val rarityPercent: Double,
)

/** Network result for one game. A null achievement list means Steam returned no usable stats. */
data class AchievementRefresh(
    val appId: Long,
    val fetchedAt: Long,
    val achievements: List<PlayerAchievementDto>?,
    val globalPercentByName: Map<String, Double>,
    val schemaByName: Map<String, AchievementSchemaDto>,
    val schemaFetchedAt: Long?,
    val fullReconciliation: Boolean = false,
    val schemaLoaded: Boolean = false,
    val account: String? = null,
    val retryAt: Long? = null,
    val playerUnavailable: Boolean = false,
)

/** Write-free result of the fetch phase used by [SteamSyncWorker]. */
data class AchievementLibraryFetch(
    val selection: AchievementFreshness.Result,
    val refreshes: List<AchievementRefresh>,
)

/** Outcome of [AchievementRepository.refreshOne] — a tier-independent, single-game fetch. */
sealed interface SingleGameRefresh {
    /** Steam returned player achievement data (possibly empty) and it was persisted. */
    data class Persisted(val total: Int, val unlocked: Int) : SingleGameRefresh

    /** Steam answered but reported no usable player data (private profile, no stats). */
    data object NoUsableData : SingleGameRefresh

    /** The request itself failed (transport error). Distinct from [NoUsableData] for messaging. */
    data object Unavailable : SingleGameRefresh
}

/**
 * Owns fetching, merging, and caching Steam achievement data. Covers every game in the
 * library — not just games the player is actively engaged with — but selects what to refresh
 * by tier (hot/warm/cold/never) and applies per-data-kind freshness windows so that inline
 * sync work is bounded and library-scale work is deferred to reconciliation.
 *
 * Each caller issues requests serially, one game at a time — see [fetchGames] for why that is a
 * decision rather than an omission. Overlapping callers share an in-flight fetch for the same
 * Steam account and app id, so widening worker concurrency does not double-spend that request.
 *
 * A per-game failure (private profile, no stats, transport error) never fails the caller —
 * it is skipped and any previously stored rows for that game are left intact.
 * [CancellationException] is rethrown so WorkManager stops promptly.
 *
 * Hidden games are excluded on both halves (add-hidden-games): their stored rows are filtered out
 * of every observed projection, and [fetchGames] — the single funnel every fetch path goes
 * through — drops them before a request is issued, so hiding a game genuinely stops costing
 * requests rather than merely hiding their results.
 */
@Singleton
class AchievementRepository @Inject constructor(
    private val steamApi: SteamApi,
    private val achievementDao: AchievementDao,
    private val gameAchievementSyncDao: GameAchievementSyncDao,
    private val gameDao: GameDao,
    private val hiddenGamesRepository: HiddenGamesRepository,
    private val time: TimeProvider,
    private val observationStore: AchievementObservationStore? = null,
    private val network: AchievementNetwork? = null,
) {

    /**
     * Exact achievements this not-yet-applied refresh newly unlocks, captured before the raw
     * transaction applies it. A mixed recovery pass excludes only these claims from its corrective
     * baseline; already-unlocked rows are not accidentally removed from historical XP.
     */
    suspend fun freshUnlockSnapshots(refreshes: List<AchievementRefresh>): Set<RecoveryAchievementSnapshot> =
        buildSet {
            for (refresh in refreshes) {
                val incoming = refresh.achievements ?: continue
                for (achievement in incoming) {
                    if (achievement.achieved <= 0) continue
                    val existing = achievementDao.getOne(refresh.appId, achievement.apiName)
                    if (existing?.unlocked == true) continue
                    add(
                        RecoveryAchievementSnapshot(
                            appId = refresh.appId,
                            apiName = achievement.apiName,
                            version = refresh.fetchedAt,
                            unlockedBefore = existing?.unlocked ?: false,
                            snapshotPercentBefore = existing?.snapshotPercent,
                        ),
                    )
                }
            }
        }

    /** Serializes merge application so a stale response cannot interleave with a newer one. */
    private val mergeMutex = Mutex()
    private data class Flight(val result: Deferred<AchievementRefresh?>, var subscribers: Int)
    private val inFlightGameFetches = mutableMapOf<FetchKey, Flight>()
    private val fetchMonitor = Any()
    private val rateLimitDeadlines = ConcurrentHashMap<FetchKey, Long>()

    fun observeForGame(appId: Long): Flow<List<GameAchievement>> {
        val catalogue = observationStore?.database?.achievementCatalogueDao()
            ?: return achievementDao.observeForGame(appId).map { rows -> rows.map(Achievement::toDomain) }
        return combine(achievementDao.observeForGame(appId), catalogue.observeEntries(appId), hiddenGamesRepository.hiddenAppIds) { rows, entries, hidden ->
            if (appId in hidden) emptyList() else {
                val byName = rows.associateBy { it.apiName }
                val objectives = entries.map { entry ->
                    byName[entry.apiName]?.toDomain() ?: GameAchievement(
                        entry.apiName, entry.displayName.ifBlank { entry.apiName }, entry.iconUrl,
                        false, null, entry.globalPercent, null, entry.description, entry.hidden, progressKnown = false,
                    )
                }
                objectives + rows.filter { row -> entries.none { it.apiName == row.apiName } }.map(Achievement::toDomain)
            }
        }
    }

    fun observeCatalogue(appId: Long): Flow<AchievementCatalogueState> {
        val catalogue = observationStore?.database?.achievementCatalogueDao()
            ?: return kotlinx.coroutines.flow.flowOf(AchievementCatalogueState())
        return combine(catalogue.observeCache(appId), catalogue.observeEntries(appId), achievementDao.observeForGame(appId)) { cache, entries, rows ->
            AchievementCatalogueState(cache?.fetchedAt, cache != null && entries.isEmpty() && rows.isEmpty(),
                if (rows.isNotEmpty()) AchievementProgressAvailability.CONFIRMED else AchievementProgressAvailability.UNKNOWN)
        }
    }

    /** One inspected game; retries end when this last subscription is cancelled. Success ends polling. */
    fun hydrateDetail(appId: Long): Flow<AchievementHydrationState> = flow {
        val store = checkNotNull(observationStore)
        val account = store.currentAccount() ?: run { emit(AchievementHydrationState.Unavailable()); return@flow }
        val cache = store.database.achievementCatalogueDao().cache(appId)
        if (cache != null && time.nowMillis() - cache.fetchedAt <= SCHEMA_WINDOW_MILLIS) {
            emit(AchievementHydrationState.Ready)
            return@flow
        }
        var failures = 0
        while (true) {
            val creds = store.currentCredentials()
            if (creds?.steamId != account || hiddenGamesRepository.isHidden(appId) || gameDao.getById(appId) == null) return@flow
            val now = time.nowMillis()
            val deadline = rateLimitDeadlines[FetchKey(account, appId)] ?: 0L
            if (network?.isOnline() == false) {
                emit(AchievementHydrationState.Unavailable(offline = true))
                delay(60_000)
                continue
            }
            if (deadline > now) {
                emit(AchievementHydrationState.Unavailable(retryAt = deadline))
                delay(deadline - now)
                continue
            }
            emit(AchievementHydrationState.Loading)
            val refresh = try { fetchGame(creds.apiKey, account, appId, null) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            if (refresh != null) {
                store.commit(account, appId, { true }) {
                    mergeMutex.withLock {
                        val before = achievementDao.getForGame(appId)
                        applyRefresh(refresh)
                        val after = achievementDao.getForGame(appId)
                        fun inputs(rows: List<Achievement>) = rows.filter { it.unlocked && !it.retired }.associate { it.apiName to it.snapshotPercent }
                        if (inputs(before) != inputs(after)) {
                            val id = canonicalObservationId(account, appId, "detail", listOf(refresh.fetchedAt.toString()))
                            store.database.achievementWatchDao().insert(AchievementWatchWork(
                                id, account, appId, "detail", refresh.fetchedAt, true,
                                epochClosed = true, recomputeSource = "XP_INTEGRITY_CORRECTION"))
                        }
                    }
                }
            }
            val nextCache = store.database.achievementCatalogueDao().cache(appId)
            if (nextCache != null && time.nowMillis() - nextCache.fetchedAt <= SCHEMA_WINDOW_MILLIS) {
                emit(AchievementHydrationState.Ready)
                return@flow
            }
            val wait = maxOf(achievementRetryDelay(failures++), (refresh?.retryAt ?: 0) - time.nowMillis())
            emit(AchievementHydrationState.Unavailable(retryAt = time.nowMillis() + wait))
            delay(wait)
        }
    }

    /** Unlocked/total achievement counts, keyed by appId — feeds the Library row badge. */
    val counts: Flow<Map<Long, AchievementCountSummary>> = achievementDao.observeCounts()
        .map { rows -> rows.associate { row -> row.appId to row.toDomain() } }
        .visibleKeys()

    /**
     * Completion inputs for derived collections. A sync row with `hasAchievements = false` is a
     * confirmed no-achievement result; a missing row or nullable legacy value remains NOT_FETCHED
     * so a playtime fallback cannot guess completion from absent data.
     */
    val smartCollectionSignals: Flow<Map<Long, SmartCollectionAchievementSignals>> = combine(
        achievementDao.observeCounts(),
        gameAchievementSyncDao.observeAll(),
    ) { counts, syncRows ->
        val countsByAppId = counts.associateBy(AchievementCounts::appId)
        syncRows.associate { row ->
            val count = countsByAppId[row.appId]
            row.appId to when (row.hasAchievements) {
                true -> SmartCollectionAchievementSignals(
                    unlocked = count?.unlocked ?: 0,
                    total = count?.total ?: 0,
                    state = AchievementDataState.HAS_ACHIEVEMENTS,
                )
                false -> SmartCollectionAchievementSignals(
                    state = AchievementDataState.NO_ACHIEVEMENTS,
                )
                null -> SmartCollectionAchievementSignals()
            }
        }
    }.visibleKeys()

    /**
     * Per-game rarity snapshots of unlocked achievements, keyed by appId — the achievement half of
     * the Library's XP badge. [counts] cannot serve here: a count of unlocked achievements is not
     * tierable, and XP is awarded per rarity tier.
     */
    val unlockedRarityByGame: Flow<Map<Long, List<Double?>>> = achievementDao.observeUnlockedRarity()
        .map { rows -> rows.groupBy(AchievementRarity::appId) { it.snapshotPercent } }
        .visibleKeys()

    /**
     * Detailed all-time rarity rows for Analytics. The existing grouped percent flow remains
     * unchanged because the Library XP engine consumes that shape; this projection adds identity
     * and game names only for the Analytics drill-down.
     */
    val unlockedRarityDetails: Flow<List<UnlockedAchievementRarity>> = combine(
        achievementDao.observeUnlockedRarity(),
        gameDao.observeLibrary(),
        hiddenGamesRepository.hiddenAppIds,
    ) { rows, games, hidden ->
        val gameNames = games.associate { it.appId to it.name }
        rows.filterNot { it.appId in hidden }.mapNotNull { row ->
            row.snapshotPercent?.let { percent ->
                UnlockedAchievementRarity(
                    appId = row.appId,
                    gameName = gameNames[row.appId] ?: "App ${row.appId}",
                    achievementName = row.displayName?.takeIf { it.isNotBlank() } ?: row.apiName,
                    rarityPercent = percent,
                )
            }
        }
    }

    /**
     * Achievements unlocked at or after [cutoffMillis], across every game — feeds the History
     * screen's per-day thumbnail row (regroup-history).
     */
    fun unlockedSince(cutoffMillis: Long): Flow<List<AchievementUnlockSummary>> =
        combine(
            achievementDao.observeUnlockedSince(cutoffMillis),
            hiddenGamesRepository.hiddenAppIds,
        ) { rows, hidden -> rows.filterNot { it.appId in hidden }.map(AchievementUnlock::toDomain) }

    /** Drops hidden games from a per-game projection, so no surface has to filter them itself. */
    private fun <T> Flow<Map<Long, T>>.visibleKeys(): Flow<Map<Long, T>> =
        combine(hiddenGamesRepository.hiddenAppIds) { byAppId, hidden ->
            if (hidden.isEmpty()) byAppId else byAppId.filterKeys { it !in hidden }
        }

    /**
     * Fetches achievements for games selected by tier: hot (playtime delta), warm (recent play),
     * and a bounded number of cold/never games with no stored achievement data.
     * [apiKey]/[steamId] are passed in by the caller (the sync worker), matching [SteamApi]'s pattern.
     */
    suspend fun syncLibraryGames(
        apiKey: String,
        steamId: String,
        ownedGames: List<AchievementFreshness.OwnedGame>,
        playtimeDeltaByAppId: Map<Long, Int>,
        scope: SyncRunRecorder.RunScope? = null,
    ): AchievementFreshness.Result {
        val fetched = fetchLibraryGames(
            apiKey = apiKey,
            steamId = steamId,
            ownedGames = ownedGames,
            playtimeDeltaByAppId = playtimeDeltaByAppId,
            scope = scope,
        )
        applyRefreshes(fetched.refreshes)
        return fetched.selection
    }

    /**
     * Fetch the inline achievement payloads without changing Room. Callers that have a larger
     * atomic commit use this phase first, then pass [AchievementLibraryFetch.refreshes] to
     * [applyRefreshes] from inside their transaction.
     */
    suspend fun fetchLibraryGames(
        apiKey: String,
        steamId: String,
        ownedGames: List<AchievementFreshness.OwnedGame>,
        playtimeDeltaByAppId: Map<Long, Int>,
        scope: SyncRunRecorder.RunScope? = null,
    ): AchievementLibraryFetch {
        // Hidden games leave the tiering input, not just the request loop, so the tier counts this
        // records describe the work actually being done (add-hidden-games).
        val hidden = hiddenGamesRepository.hiddenAppIdSet()
        val visibleGames = if (hidden.isEmpty()) ownedGames else ownedGames.filterNot { it.appId in hidden }
        if (visibleGames.isEmpty()) {
            return AchievementLibraryFetch(
                selection = AchievementFreshness.Result(
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                ),
                refreshes = emptyList(),
            )
        }

        val metadataByAppId = gameAchievementSyncDao.getAll(visibleGames.map { it.appId }.toSet())
            .associateBy { it.appId }
            .mapValues {
                AchievementFreshness.SyncMetadata(
                    it.key,
                    it.value.playerStateFetchedAt,
                    it.value.schemaFetchedAt,
                )
            }

        val selection = AchievementFreshness.selectByTier(
            now = time.nowMillis(),
            ownedGames = visibleGames,
            playtimeDeltaByAppId = playtimeDeltaByAppId,
            metadataByAppId = metadataByAppId,
        )

        val refreshes = fetchGames(
            apiKey = apiKey,
            steamId = steamId,
            appIds = selection.inlineSelected,
            schemaFetchedAtByAppId = selection.inlineSelected.associateWith {
                metadataByAppId[it]?.schemaFetchedAt
            },
            scope = scope,
        )

        return AchievementLibraryFetch(selection, refreshes)
    }

    /** Apply already-fetched achievement payloads. The caller may surround this with a Room transaction. */
    suspend fun applyRefreshes(refreshes: List<AchievementRefresh>) {
        mergeMutex.withLock {
            for (refresh in refreshes) applyRefresh(refresh)
        }
    }

    /**
     * Fetch and persist one game's achievements outside of tier selection, ignoring playtime
     * entirely. Used where tier eligibility does not apply — e.g. a family-shared game's
     * `GetPlayerAchievements` answer does not depend on Backlogium having observed a session for
     * it, unlike [syncLibraryGames]'s hot/warm/cold/never classification (fix-shared-game-achievement-visibility).
     *
     * Persists through the same [applyRefresh] merge path a normal sync uses, so the caller never
     * needs to touch [AchievementMerge] or the DAOs directly.
     *
     * Hidden games never reach the network: a hidden [appId] returns [SingleGameRefresh.NoUsableData]
     * without issuing a request, matching the fetch funnel's guarantee for batched paths.
     */
    suspend fun refreshOne(
        apiKey: String,
        steamId: String,
        appId: Long,
        scope: SyncRunRecorder.RunScope? = null,
    ): SingleGameRefresh {
        if (hiddenGamesRepository.isHidden(appId)) return SingleGameRefresh.NoUsableData
        val metadata = gameAchievementSyncDao.get(appId)
        val refresh = try {
            fetchGame(
                apiKey = apiKey,
                steamId = steamId,
                appId = appId,
                schemaFetchedAt = metadata?.schemaFetchedAt,
                scope = scope,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return SingleGameRefresh.Unavailable
        } ?: return SingleGameRefresh.NoUsableData

        val store = observationStore
        if (store != null) store.commit(steamId, appId, { true }) { mergeMutex.withLock { applyRefresh(refresh) } }
        else mergeMutex.withLock { applyRefresh(refresh) }
        if (refresh.achievements == null) return if (refresh.playerUnavailable) SingleGameRefresh.Unavailable else SingleGameRefresh.NoUsableData
        val achievements = refresh.achievements.orEmpty()
        return SingleGameRefresh.Persisted(
            total = achievements.size,
            unlocked = achievements.count { it.achieved != 0 },
        )
    }

    /** A write-free read shared with sync/manual/detail work. Never advances a watch baseline. */
    suspend fun prepareWatch(
        apiKey: String,
        steamId: String,
        appId: Long,
        scope: SyncRunRecorder.RunScope? = null,
    ): AchievementRefresh? {
        if (hiddenGamesRepository.isHidden(appId)) return null
        return fetchGame(apiKey, steamId, appId, gameAchievementSyncDao.get(appId)?.schemaFetchedAt, scope)
    }

    sealed interface WatchCommit {
        data class Committed(val unlocked: Set<String>, val changedXp: Boolean, val eventId: String?) : WatchCommit
        data object DeferredRarity : WatchCommit
        data object Stale : WatchCommit
    }

    /** Validate and merge under the same account/Room boundary as the outbox insert. */
    suspend fun commitWatch(
        account: String,
        epoch: String,
        refresh: AchievementRefresh,
        baseline: Set<String>?,
        valid: () -> Boolean,
    ): WatchCommit {
        val store = checkNotNull(observationStore)
        return store.commit(account, refresh.appId, valid, watch = true) {
            mergeMutex.withLock {
                val incoming = refresh.achievements ?: return@withLock WatchCommit.Stale
                val metadata = gameAchievementSyncDao.get(refresh.appId)
                val previous = achievementDao.getForGame(refresh.appId)
                if (!valid() || (metadata?.playerStateFetchedAt ?: Long.MIN_VALUE) > refresh.fetchedAt ||
                    previous.any { it.fetchedAt > refresh.fetchedAt }
                ) return@withLock WatchCommit.Stale
                // An empty response contradicting real rows is not a usable baseline.
                if (incoming.isEmpty() && previous.isNotEmpty()) return@withLock WatchCommit.Stale
                val byName = previous.associateBy { it.apiName }
                if (incoming.any { it.achieved != 0 && byName[it.apiName]?.snapshotPercent == null &&
                        refresh.globalPercentByName[it.apiName]?.let { percent -> percent.isFinite() && percent in 0.0..100.0 } != true
                    }
                ) return@withLock WatchCommit.DeferredRarity
                val unlocked = incoming.filter { it.achieved != 0 }.mapTo(sortedSetOf()) { it.apiName }
                val newlyObserved = if (baseline == null) emptySet() else unlocked - baseline
                applyRefresh(refresh.copy(fullReconciliation = false))
                val after = achievementDao.getForGame(refresh.appId)
                fun xpInputs(rows: List<Achievement>) = rows.filter { it.unlocked && !it.retired }
                    .associate { it.apiName to it.snapshotPercent }
                val changedXp = xpInputs(previous) != xpInputs(after)
                val eventId = canonicalObservationId(account, refresh.appId, epoch,
                    if (newlyObserved.isEmpty()) listOf("observation", refresh.fetchedAt.toString()) else newlyObserved.sorted())
                val group = if (newlyObserved.isEmpty()) null else AchievementUnlockGroup(
                    eventId, account, refresh.appId,
                    gameDao.getById(refresh.appId)?.name ?: "App ${refresh.appId}", refresh.fetchedAt,
                    after.filter { it.apiName in newlyObserved && !it.retired }.sortedBy { it.apiName }.map {
                        AchievementUnlockItem(it.apiName, it.displayName ?: it.apiName, it.iconUrl, it.unlockedAt, it.snapshotPercent)
                    },
                )
                if (changedXp || group != null) {
                    store.database.achievementWatchDao().insert(
                        AchievementWatchWork(eventId, account, refresh.appId, epoch, refresh.fetchedAt,
                            changedXp, group?.let { Json.encodeToString(it) }),
                    )
                }
                currentCoroutineContext().ensureActive()
                if (!valid()) throw CancellationException("Watch generation closed before commit")
                WatchCommit.Committed(unlocked, changedXp, group?.eventId)
            }
        } ?: WatchCommit.Stale
    }

    data class ReconciliationResult(
        val refreshed: Int,
        val total: Int,
    )

    data class ReconciliationFetch(
        val refreshed: Int,
        val total: Int,
    )

    /**
     * Deferred reconciliation pass: refresh every cold game (played at some point, but not warm
     * and not played since the last sync) ordered by oldest `playerStateFetchedAt` first. Each
     * successful refresh updates that timestamp, so an interrupted pass naturally resumes with
     * the games it did not yet reach rather than restarting.
     *
     * [onProgress], if given, fires after every game attempt with the running refreshed/total
     * counts — the only way a caller can know how far the pass got if it is cancelled mid-sweep.
     *
     * @return counts of games refreshed and total cold games considered
     */
    suspend fun reconcileLibraryGames(
        apiKey: String,
        steamId: String,
        scope: SyncRunRecorder.RunScope? = null,
        onProgress: ((refreshed: Int, total: Int) -> Unit)? = null,
    ): ReconciliationResult {
        val fetched = fetchReconciliationGames(
            apiKey = apiKey,
            steamId = steamId,
            scope = scope,
            onRefresh = { refresh ->
                withContext(NonCancellable) {
                    applyRefreshes(listOf(refresh))
                }
            },
            onProgress = onProgress,
        )
        return ReconciliationResult(fetched.refreshed, fetched.total)
    }

    /**
     * Fetch and hand off each full-reconciliation payload before the next game starts.
     *
     * The callback is the persistence boundary. Callers that own a larger transaction may commit
     * the one refresh there; the worker uses a non-cancellable transaction so a completed fetch is
     * never stranded in an in-memory list when WorkManager cancels the sweep.
     */
    suspend fun fetchReconciliationGames(
        apiKey: String,
        steamId: String,
        scope: SyncRunRecorder.RunScope? = null,
        onRefresh: suspend (AchievementRefresh) -> Unit,
        onProgress: ((refreshed: Int, total: Int) -> Unit)? = null,
    ): ReconciliationFetch {
        val hidden = hiddenGamesRepository.hiddenAppIdSet()
        val games = gameDao.getAll()
        val visibleGames = if (hidden.isEmpty()) games else games.filterNot { it.appId in hidden }
        if (visibleGames.isEmpty()) return ReconciliationFetch(refreshed = 0, total = 0)

        val metadataByAppId = gameAchievementSyncDao.getAll(visibleGames.map { it.appId }.toSet())
            .associateBy { it.appId }

        val ownedGames = visibleGames.map {
            AchievementFreshness.OwnedGame(
                appId = it.appId,
                playtimeForever = it.playtimeForever.toLong(),
                playtime2Weeks = it.playtime2Weeks.toLong(),
                source = it.source,
            )
        }
        // Reuses tiering's own cold-tier rule so this pass's population can never drift from what
        // the inline sync considers cold; delta/metadata inputs are irrelevant to that rule.
        val cold = AchievementFreshness.selectByTier(
            now = time.nowMillis(),
            ownedGames = ownedGames,
            playtimeDeltaByAppId = emptyMap(),
            metadataByAppId = emptyMap(),
        ).cold.sortedBy { metadataByAppId[it]?.playerStateFetchedAt ?: Long.MIN_VALUE }

        // Report the total up front too, so a cancellation before any single game finishes still
        // leaves the caller with an accurate total rather than the callback's implicit initial 0.
        onProgress?.invoke(0, cold.size)
        var refreshed = 0
        fetchGames(
            apiKey = apiKey,
            steamId = steamId,
            appIds = cold,
            schemaFetchedAtByAppId = cold.associateWith { metadataByAppId[it]?.schemaFetchedAt },
            scope = scope,
            onRefresh = { refresh ->
                onRefresh(refresh)
                if (refresh.achievements != null) refreshed++
            },
            onGameDone = { refreshedSoFar -> onProgress?.invoke(refreshedSoFar, cold.size) },
            fullReconciliation = true,
        )
        return ReconciliationFetch(refreshed = refreshed, total = cold.size)
    }

    /**
     * Fetches [appIds] **serially** — one request in flight at a time. Hidden games are skipped
     * before any request is made.
     *
     * This is deliberate, not a missing optimisation. Tiering is what made fetch volume small: a
     * steady-state sync now selects a handful of played games, so a typical inline pass is a few
     * requests and there is nothing left for concurrency to speed up. The only pass large enough to
     * care is the weekly reconciliation, which runs on charger + unmetered wifi precisely so its
     * duration can be irrelevant. That leaves concurrency with real burst exposure against a Steam
     * client that has no retry, backoff, or 429 handling, in exchange for a speedup nothing needs —
     * and it is the same courtesy-not-throughput reasoning design.md used to pick a modest bound in
     * the first place, carried to its conclusion. The HLTB batch path serialises for the same
     * reason (it goes further and sleeps between requests).
     *
     * A per-game failure is swallowed so one bad response cannot abort the batch;
     * [CancellationException] is always rethrown so callers stop promptly. [onGameDone], if given,
     * fires after every game [syncGame] actually updates — not merely attempted — with the
     * running refreshed count.
     *
     * Hidden games are dropped before any request is issued, so hiding a game stops costing
     * requests rather than merely hiding their results.
     */
    private suspend fun fetchGames(
        apiKey: String,
        steamId: String,
        appIds: List<Long>,
        schemaFetchedAtByAppId: Map<Long, Long?>,
        scope: SyncRunRecorder.RunScope? = null,
        onRefresh: (suspend (AchievementRefresh) -> Unit)? = null,
        onGameDone: ((refreshedSoFar: Int) -> Unit)? = null,
        fullReconciliation: Boolean = false,
    ): List<AchievementRefresh> {
        val hidden = hiddenGamesRepository.hiddenAppIdSet()
        val visibleAppIds = if (hidden.isEmpty()) appIds else appIds.filterNot { it in hidden }
        val refreshes = mutableListOf<AchievementRefresh>()
        var refreshedSoFar = 0
        for (appId in visibleAppIds) {
            val refresh = try {
                fetchGame(
                    apiKey = apiKey,
                    steamId = steamId,
                    appId = appId,
                    schemaFetchedAt = schemaFetchedAtByAppId[appId],
                    scope = scope,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Per-game failure is swallowed so one bad response cannot fail the batch.
                null
            }
            if (refresh != null) {
                val completed = refresh.copy(fullReconciliation = fullReconciliation)
                if (onRefresh != null) {
                    // A persistence callback is deliberately outside the per-game failure catch:
                    // database failures must fail the worker and be retried, not be misreported as
                    // an innocuous Steam game that had no stats.
                    onRefresh(completed)
                } else {
                    refreshes += completed
                }
                if (refresh.achievements != null) refreshedSoFar++
                onGameDone?.invoke(refreshedSoFar)
            }
        }
        return refreshes
    }

    /** @return a write-free payload if this game's response is usable. */
    private suspend fun fetchGame(
        apiKey: String,
        steamId: String,
        appId: Long,
        schemaFetchedAt: Long?,
        scope: SyncRunRecorder.RunScope? = null,
    ): AchievementRefresh? {
        val key = FetchKey(steamId = steamId, appId = appId)
        val context = currentCoroutineContext()
        val flight = synchronized(fetchMonitor) {
            inFlightGameFetches[key]?.also { it.subscribers++ } ?: run {
                val job = SupervisorJob()
                val result = CoroutineScope(context.minusKey(Job) + job).async(start = CoroutineStart.LAZY) {
                    try {
                        fetchGameUncoordinated(apiKey, steamId, appId, schemaFetchedAt, scope)
                    } finally { job.complete() }
                }
                Flight(result, 1).also { inFlightGameFetches[key] = it }
            }
        }
        try {
            return flight.result.await()
        } finally {
            synchronized(fetchMonitor) {
                flight.subscribers--
                if (flight.subscribers == 0) {
                    inFlightGameFetches.remove(key, flight)
                    flight.result.cancel()
                }
            }
        }
    }

    private data class FetchKey(val steamId: String, val appId: Long)

    private suspend fun fetchGameUncoordinated(
        apiKey: String,
        steamId: String,
        appId: Long,
        schemaFetchedAt: Long?,
        scope: SyncRunRecorder.RunScope? = null,
    ): AchievementRefresh? {
        val now = time.nowMillis()
        val key = FetchKey(steamId, appId)
        val deadline = rateLimitDeadlines[key] ?: 0
        if (deadline > now) return AchievementRefresh(appId, now, null, emptyMap(), emptyMap(), schemaFetchedAt,
            account = steamId, retryAt = deadline)
        var retryAt: Long? = null
        var playerFailure: Exception? = null
        suspend fun <T> attempt(block: suspend () -> T): T? = try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                achievementRetryAfter(error, now)?.let {
                    retryAt = maxOf(retryAt ?: 0, it)
                    rateLimitDeadlines.merge(key, it, ::maxOf)
                }
                null
            }
        val cachedAt = observationStore?.database?.achievementCatalogueDao()?.cache(appId)?.fetchedAt
        val effectiveSchemaAt = if (observationStore != null) cachedAt else schemaFetchedAt
        val schemaWasStale = effectiveSchemaAt == null || now - effectiveSchemaAt > SCHEMA_WINDOW_MILLIS
        val schema = if (schemaWasStale) attempt {
            steamApi.getSchemaForGame(apiKey, appId, scope).game.availableGameStats?.achievements
        } else null
        val player = if (retryAt == null) attempt {
            try { steamApi.getPlayerAchievements(apiKey, steamId, appId, scope).playerstats }
            catch (error: Exception) { playerFailure = error; throw error }
        } else null
        val globals = if (retryAt == null && (!schema.isNullOrEmpty() || !player?.achievements.isNullOrEmpty())) attempt {
            steamApi.getGlobalAchievementPercentages(appId, scope).achievementpercentages.achievements
                .filter { it.percent.isFinite() && it.percent in 0.0..100.0 }.associate { it.name to it.percent }
        }.orEmpty() else emptyMap()
        val knownCatalogue = schema ?: observationStore?.database?.achievementCatalogueDao()?.entries(appId)?.map {
            AchievementSchemaDto(it.apiName, it.displayName, it.iconUrl.orEmpty(), it.description.orEmpty(), if (it.hidden) 1 else 0)
        }
        val existing = achievementDao.getForGame(appId)
        val usablePlayer = player?.takeIf { it.success }?.achievements?.takeUnless {
            it.isEmpty() && (!knownCatalogue.isNullOrEmpty() || existing.isNotEmpty())
        }
        if (usablePlayer == null && observationStore == null) {
            playerFailure?.let { throw it }
            return null
        }
        currentCoroutineContext().ensureActive()
        return AchievementRefresh(appId, now, usablePlayer, globals, schema?.associateBy { it.name }.orEmpty(),
            if (schema != null) now else schemaFetchedAt, schemaLoaded = schema != null, account = steamId, retryAt = retryAt, playerUnavailable = playerFailure != null)
    }

    /**
     * Merge a payload inside the caller's transaction. Timestamp guards prevent an older fetch
     * that completes later from replacing a newer observation or retiring rows it did not see.
     */
    private suspend fun applyRefresh(refresh: AchievementRefresh) {
        val db = observationStore?.database
        if (db != null && refresh.account != null) {
            if (db.playerProfileDao().get()?.steamId != refresh.account || gameDao.getById(refresh.appId) == null ||
                hiddenGamesRepository.isHidden(refresh.appId)) return
        }
        val catalogue = db?.achievementCatalogueDao()
        val previousRows = achievementDao.getForGame(refresh.appId)
        if (catalogue != null && refresh.schemaLoaded &&
            (catalogue.cache(refresh.appId)?.fetchedAt ?: Long.MIN_VALUE) <= refresh.fetchedAt &&
            (refresh.schemaByName.isNotEmpty() || (previousRows.isEmpty() && refresh.achievements.isNullOrEmpty()))
        ) {
            catalogue.clearEntries(refresh.appId)
            catalogue.upsert(refresh.schemaByName.values.map { schema -> AchievementCatalogueEntry(
                refresh.appId, schema.name, schema.displayName, schema.icon.takeIf { it.isNotBlank() },
                schema.description.takeIf { it.isNotBlank() }, schema.hidden != 0, refresh.globalPercentByName[schema.name],
            ) })
            catalogue.upsert(AchievementCatalogueCache(refresh.appId, refresh.fetchedAt))
        }
        val metadata = gameAchievementSyncDao.get(refresh.appId)
        if ((metadata?.playerStateFetchedAt ?: Long.MIN_VALUE) > refresh.fetchedAt) return

        val existing = achievementDao.getForGame(refresh.appId)
        val existingByName = existing.associateBy { it.apiName }
        val incoming = refresh.achievements ?: return
        if (incoming.isEmpty() && (existing.isNotEmpty() || refresh.schemaByName.isNotEmpty() ||
                catalogue?.entries(refresh.appId)?.isNotEmpty() == true)) return
        val incomingNames = incoming.mapTo(mutableSetOf()) { it.apiName }
        val rows = if (incoming.isEmpty()) {
            emptyList()
        } else {
            incoming.mapNotNull { dto ->
                val prior = existingByName[dto.apiName]
                if (prior != null && prior.fetchedAt > refresh.fetchedAt) {
                    null
                } else {
                    com.example.backlogium.data.achievement.AchievementMerge.merge(
                        appId = refresh.appId,
                        dto = dto,
                        globalPercent = refresh.globalPercentByName[dto.apiName],
                        schema = refresh.schemaByName[dto.apiName],
                        prior = prior,
                        now = refresh.fetchedAt,
                    ).copy(retired = false)
                }
            }
        }
        val retired = if (refresh.fullReconciliation) {
            existing.filter {
                it.apiName !in incomingNames && it.fetchedAt <= refresh.fetchedAt
            }.map { it.copy(retired = true) }
        } else {
            emptyList()
        }
        val rowsToWrite = rows + retired
        if (rowsToWrite.isNotEmpty()) achievementDao.upsertAll(rowsToWrite)
        gameAchievementSyncDao.upsert(
            GameAchievementSync(
                appId = refresh.appId,
                playerStateFetchedAt = refresh.fetchedAt,
                schemaFetchedAt = refresh.schemaFetchedAt,
                hasAchievements = incoming.isNotEmpty(),
                checkedAt = refresh.fetchedAt,
            ),
        )
    }

    companion object {
        /** How long a fetched achievement schema remains valid; schema changes only when patched by the developer. */
        const val SCHEMA_WINDOW_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}

private fun AchievementCounts.toDomain() = AchievementCountSummary(
    appId = appId,
    total = total,
    unlocked = unlocked,
)

private fun AchievementUnlock.toDomain() = AchievementUnlockSummary(
    appId = appId,
    iconUrl = iconUrl,
    unlockedAt = unlockedAt,
)

private fun Achievement.toDomain() = GameAchievement(
    apiName = apiName,
    displayName = displayName?.takeIf { it.isNotBlank() } ?: apiName,
    iconUrl = iconUrl,
    unlocked = unlocked,
    // The snapshot, never globalPercent: the rarity-drift policy pins tier/XP to first unlock.
    rarityPercent = snapshotPercent,
    // Carried alongside for display/sorting only — locked rows have no snapshot to show or sort by.
    globalPercent = globalPercent,
    unlockedAt = unlockedAt,
    description = description?.takeIf { it.isNotBlank() },
    hidden = hidden,
)

/** Length-prefixed components avoid ambiguous account/game/achievement concatenations. */
internal fun canonicalObservationId(account: String, appId: Long, epoch: String, names: List<String>): String {
    val canonical = (listOf(account, appId.toString(), epoch) + names.sorted()).joinToString("") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
