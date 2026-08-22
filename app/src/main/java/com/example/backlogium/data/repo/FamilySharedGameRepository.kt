package com.example.backlogium.data.repo

import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.dao.ExcludedSharedGameDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.entity.ExcludedSharedGame
import com.example.backlogium.domain.AdmissionDecision
import com.example.backlogium.domain.AdmissionFacts
import com.example.backlogium.domain.SharedGameAdmissionPolicy
import com.example.backlogium.domain.StoreVerification
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** A family-shared game the player removed, as Settings sees it. Never the storage entity. */
data class RemovedSharedGame(
    val appId: Long,
    val name: String,
    val removedAt: Long,
)

/**
 * Admission, removal, and re-admission of family-shared games (add-family-shared-games).
 *
 * A borrowed game is absent from `GetOwnedGames` and therefore from every path that creates a game
 * row today, so the only evidence it exists is that Steam reports the player inside it. This
 * repository turns that observation into a tracked game — or explains why it did not — and owns the
 * sticky exclusion list that a removal writes to.
 *
 * The decision itself lives in [SharedGameAdmissionPolicy], a pure function; everything here is the
 * IO around it.
 */
@Singleton
class FamilySharedGameRepository @Inject constructor(
    private val gameDao: GameDao,
    private val excludedDao: ExcludedSharedGameDao,
    private val profileDao: PlayerProfileDao,
    private val settings: SettingsDataStore,
    private val store: SteamStoreAppDataSource,
    private val genres: GameGenreRepository,
    private val policy: SharedGameAdmissionPolicy,
    private val notifier: SharedGameNotifier,
    private val time: TimeProvider,
) {

    /** Removed games, newest first. Empty when nothing has been removed, so Settings can hide. */
    val removedGames: Flow<List<RemovedSharedGame>> = excludedDao.observeAll().map { rows ->
        rows.map { RemovedSharedGame(it.appId, it.name, it.excludedAt) }
    }

    /**
     * Consider an app id observed in presence that has no `games` row.
     *
     * Safe and cheap to call on every observation: the first call records the app id as a candidate
     * and stops, and only a later call — after a sync has since confirmed the game is genuinely not
     * owned — reaches the one store request. Every rejection is reported rather than swallowed so
     * the caller can leave the observation alone and reconsider it next time.
     *
     * @return the decision reached. [AdmissionDecision.Admit] means a game row now exists.
     */
    suspend fun considerAdmission(appId: Long, observedAt: Long): AdmissionDecision {
        val candidate = settings.sharedGameCandidateFlow.first()
        // A new app id restarts the clock: the sync that matters is one that completed after *this*
        // id was first seen, and the previous candidate is worth nothing once play has moved on.
        val firstObservedAt = if (candidate?.appId == appId) candidate.firstObservedAt else {
            settings.setSharedGameCandidate(appId, observedAt)
            observedAt
        }

        val facts = AdmissionFacts(
            appId = appId,
            isTracked = gameDao.getById(appId) != null,
            isExcluded = excludedDao.isExcluded(appId),
            firstObservedAt = firstObservedAt,
            lastSuccessfulSyncAt = profileDao.get()?.lastSyncAt ?: 0L,
        )

        val screened = policy.evaluate(facts)
        if (screened != AdmissionDecision.NeedsStoreVerification) {
            // Nothing further will change for a permanently rejected id; stop carrying it as a
            // candidate so a later, genuinely new observation isn't measured against a stale clock.
            if (screened == AdmissionDecision.AlreadyTracked || screened == AdmissionDecision.Excluded) {
                clearCandidateIfCurrent(appId)
            }
            return screened
        }

        val info = store.appInfoFor(appId)
        val verified = policy.evaluate(
            facts.copy(
                store = when (info) {
                    is StoreAppInfo.Game -> StoreVerification.GAME
                    StoreAppInfo.NotAGame -> StoreVerification.NOT_A_GAME
                    is StoreAppInfo.Unavailable -> StoreVerification.UNAVAILABLE
                },
            ),
        )
        if (verified != AdmissionDecision.Admit || info !is StoreAppInfo.Game) return verified

        admit(appId, info, observedAt)
        return AdmissionDecision.Admit
    }

    /**
     * Remove a family-shared game and record the exclusion, so further play does not re-admit it.
     * The game row is deleted, and its sessions cascade with it — the player asked for the game to
     * stop being tracked, and leaving orphaned history behind would contradict that while still
     * feeding XP totals.
     *
     * A no-op for a game whose source is owned: `deleteSharedGame` is guarded in SQL, and the
     * exclusion is only written when a row was actually removed. The library's contents are Steam's
     * to decide, not the app's.
     *
     * @return true when a family-shared game was removed.
     */
    suspend fun remove(appId: Long): Boolean {
        val game = gameDao.getById(appId) ?: return false
        if (gameDao.deleteSharedGame(appId) == 0) return false
        excludedDao.upsert(
            ExcludedSharedGame(appId = appId, name = game.name, excludedAt = time.nowMillis()),
        )
        clearCandidateIfCurrent(appId)
        return true
    }

    /**
     * Reverse a removal. The game is not recreated here: it becomes eligible again and is admitted
     * the next time it is observed being played, which is the same path that admitted it the first
     * time. Recreating the row directly would invent a tracked game from a list entry rather than
     * from an observation.
     */
    suspend fun reverseRemoval(appId: Long) {
        excludedDao.delete(appId)
        clearCandidateIfCurrent(appId)
    }

    private suspend fun admit(appId: Long, info: StoreAppInfo.Game, observedAt: Long) {
        // Icon hash comes from GetOwnedGames, which by definition has nothing to say about a
        // borrowed game. Left blank: header and capsule artwork are derived from the app id alone,
        // so the game still arrives with artwork, and every icon consumer already treats a blank
        // icon as "use the derived art".
        gameDao.insertSharedGameIfMissing(
            appId = appId,
            name = info.name,
            iconUrl = "",
            admittedAt = observedAt,
        )
        // One announcement per admission. `INSERT OR IGNORE` above makes a second observation of an
        // already-admitted game a no-op, and the policy's AlreadyTracked screen means this line is
        // not reached for one at all — a game observed again is rejected before any of this runs.
        // Best-effort: a genre-cache write failure must not undo an otherwise-valid admission,
        // and background enrichment would resolve them later anyway.
        runCatching { genres.storeGenres(appId, info.genres) }
        clearCandidateIfCurrent(appId)
        notifier.notifyAdmitted(appId, info.name)
    }

    private suspend fun clearCandidateIfCurrent(appId: Long) {
        if (settings.sharedGameCandidateFlow.first()?.appId == appId) {
            settings.clearSharedGameCandidate()
        }
    }
}
