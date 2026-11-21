package com.example.backlogium.domain

import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.backup.DatabaseTransactionScope
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.work.SteamSyncCoordinator
import kotlinx.coroutines.flow.first
import javax.inject.Inject

enum class PlaytimeBackfillResetResult {
    RESET,
    BLOCKED_BY_CLOUD_TRANSFER,
    NO_OP,
}

/**
 * One-time, opt-in import of historical Steam playtime into XP (add-playtime-backfill).
 *
 * For each game it freezes the historical portion —
 * `backfillMinutes = max(0, playtimeForever − creditedMinutes(appId))` — onto the `Game` row,
 * marks the profile flag, and triggers a recompute. Freezing the offset (rather than feeding
 * the live, ever-growing `playtimeForever`) makes the import genuinely one-time: ongoing
 * tracked sessions accrue on top, and later syncs never re-import.
 *
 * The subtraction uses the *canonical credited* total (`SessionDao.creditedMinutesByGame()`,
 * recover-missed-playtime Decision 7), so a recovery bucket is never double-counted against the
 * frozen lifetime offset; on the reset path the cleared offset is simply dropped.
 *
 * The engine is unchanged: [GamificationUpdater.recompute] simply feeds
 * `backfillMinutes + creditedMinutes` as one cumulative total, which the existing per-game
 * taper bounds naturally.
 */
class PlaytimeBackfillUseCase @Inject constructor(
    private val gameDao: GameDao,
    private val sessionDao: SessionDao,
    private val playerProfileDao: PlayerProfileDao,
    private val settings: SettingsDataStore,
    private val gamificationUpdater: GamificationUpdater,
    private val time: TimeProvider,
    private val derivedStateWrites: DerivedStateWriteCoordinator,
    private val syncCoordinator: SteamSyncCoordinator,
    private val transaction: DatabaseTransactionScope,
    private val credentials: CredentialsProvider,
    private val accountChangeMarker: AccountChangeMarkerStore,
) {

    /**
     * Import historical playtime if not already done.
     *
     * @return `true` if this call performed the import, `false` if it was a no-op because
     *   history had already been imported (idempotence guarantee).
     */
    suspend operator fun invoke(): Boolean = syncCoordinator.withLock {
        check(accountChangeMarker.pendingSteamId() == null) { "An account change is still in progress." }
        val account = credentials.currentCredentials()?.steamId
            ?: error("Connect your Steam account before importing history.")
        derivedStateWrites.withLock {
            // The offsets, completion flag and pending-derived marker commit together. Sharing the
            // raw ledger transaction prevents an overlapping poll from being subtracted halfway.
            // Lock order matches sync: raw-ledger admission before derived-state writes.
            val imported = transaction.run {
                val profile = playerProfileDao.get()
                check(profile?.steamId == account && profile.lastSyncAt > 0L) {
                    "Finish syncing your Steam library before importing history."
                }
                if (profile.playtimeBackfilled) {
                    false
                } else {
                    val trackedByGame = sessionDao.creditedMinutesByGame().associate { it.appId to it.minutes }
                    val backfillByAppId = gameDao.getAll().associate { game ->
                        val tracked = trackedByGame[game.appId] ?: 0
                        game.appId to (game.playtimeForever - tracked).coerceAtLeast(0)
                    }
                    gameDao.applyBackfill(backfillByAppId)
                    playerProfileDao.updatePlaytimeBackfilled(true)
                    playerProfileDao.markPendingImportRecompute()
                    true
                }
            }
            // Repeating an interrupted import must finish derived work, not just return a no-op.
            // Startup also resumes this existing Room marker via PendingImportRecomputeUseCase.
            if (playerProfileDao.get()?.pendingImportRecompute == true) recompute()
            imported
        }
    }

    /**
     * Undo the import: clear every frozen offset, unset the flag, and recompute so XP falls
     * back to tracked-only. After this the import can be offered — and run — again, which
     * refreezes the same historical portion (`playtimeForever − trackedMinutes` is a set, not
     * an add, so re-importing is safe and restores the prior result). Tracked sessions, daily
     * progress, and streaks are untouched.
     */
    suspend fun reset(): PlaytimeBackfillResetResult = syncCoordinator.withLock {
        derivedStateWrites.withLock {
            val refilingApplied = settings.cloudPresenceRefilingAppliedFlow.first()
            val receipt = settings.cloudPresenceRefilingReceiptFlow.first()
            if (refilingApplied && receipt?.transferredMinutesByAppId?.any { it.minutes > 0 } == true) {
                return@withLock PlaytimeBackfillResetResult.BLOCKED_BY_CLOUD_TRANSFER
            }

            val profile = playerProfileDao.get()
            if (profile == null) return@withLock PlaytimeBackfillResetResult.NO_OP

            transaction.run {
                gameDao.applyBackfill(gameDao.getAll().associate { it.appId to 0 })
                playerProfileDao.updatePlaytimeBackfilled(false)
                playerProfileDao.markPendingImportRecompute()
            }
            recompute()
            PlaytimeBackfillResetResult.RESET
        }
    }

    private suspend fun recompute() {
        val rules = settings.ruleConfigWithVersionFlow.first()
        gamificationUpdater.recompute(
            today = time.today(),
            source = RecomputeSource.BACKFILL,
            config = rules.config,
            configVersion = rules.version,
        )
    }
}
