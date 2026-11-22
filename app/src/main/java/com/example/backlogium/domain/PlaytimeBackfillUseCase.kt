package com.example.backlogium.domain

import com.example.backlogium.data.backup.DatabaseTransactionScope
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.LibraryPollCommitDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.setup.SetupOwner
import com.example.backlogium.work.SteamSyncCoordinator
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.first

enum class PlaytimeBackfillResetResult {
    RESET,
    BLOCKED_BY_CLOUD_TRANSFER,
    NO_OP,
}

data class HistoryImportRequest(val id: String, val owner: SetupOwner)

/** Explicit consent and frozen history capture shared by onboarding and Settings. */
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
    private val libraryCommits: LibraryPollCommitDao,
) {
    suspend operator fun invoke(): Boolean {
        val request = request() ?: return false
        return resume(request)
    }

    /** The user may leave as soon as this returns. The request is durable before any capture. */
    suspend fun request(expectedOwner: SetupOwner? = null): HistoryImportRequest? = syncCoordinator.withLock {
        accountChangeMarker.withAccountState {
            val owner = checkNotNull(activeOwner()) { "Connect your Steam account before importing history." }
            check(expectedOwner == null || owner == expectedOwner) { "The Steam account changed." }
            val profile = playerProfileDao.get()
            val activeProfile = profile?.takeIf { it.steamId == owner.steamId }
            activeProfile?.requestFor(owner)?.let { return@withAccountState it }
            if (activeProfile?.playtimeBackfilled == true) return@withAccountState null
            check(activeProfile != null && libraryCommits.hasBaseline(owner.steamId)) {
                "Finish syncing your Steam library before importing history."
            }

            val request = HistoryImportRequest(UUID.randomUUID().toString(), owner)
            playerProfileDao.recordHistoryConsent(owner.steamId, request.id, owner.generation)
            request
        }
    }

    /** Called on startup and from either surface's recovery action. Returns true for a new capture. */
    suspend fun resume(
        expected: HistoryImportRequest? = null,
        expectedOwner: SetupOwner? = expected?.owner,
    ): Boolean = syncCoordinator.withLock {
        derivedStateWrites.withLock {
            var request: HistoryImportRequest? = null
            val captured = accountChangeMarker.withAccountState account@{
                val owner = activeOwner() ?: return@account false
                if (expectedOwner != null && expectedOwner != owner) return@account false
                val profile = playerProfileDao.get() ?: return@account false
                val admitted = profile.requestFor(owner) ?: return@account false
                if (expected != null && admitted != expected) return@account false
                request = admitted
                if (profile.playtimeBackfilled) {
                    if (!profile.historyImportCaptured && !profile.pendingImportRecompute) {
                        playerProfileDao.finishHistoryImport(owner.steamId, admitted.id)
                        request = null
                    }
                    return@account false
                }
                if (!libraryCommits.hasBaseline(owner.steamId)) {
                    request = null
                    return@account false
                }

                transaction.run {
                    val credited = sessionDao.creditedMinutesByGame().associate { it.appId to it.minutes }
                    val offsets = gameDao.getAll().associate { game ->
                        val historical = (game.playtimeForever - (credited[game.appId] ?: 0)).coerceAtLeast(0)
                        game.appId to (game.backfillMinutes.takeIf { it > 0 } ?: historical)
                    }
                    gameDao.applyBackfill(offsets)
                    playerProfileDao.markHistoryImportCaptured(owner.steamId, admitted.id)
                }
                true
            }
            request?.let { recompute(it.id) }
            captured
        }
    }

    suspend fun reset(): PlaytimeBackfillResetResult = syncCoordinator.withLock {
        derivedStateWrites.withLock {
            val refilingApplied = settings.cloudPresenceRefilingAppliedFlow.first()
            val receipt = settings.cloudPresenceRefilingReceiptFlow.first()
            if (refilingApplied && receipt?.transferredMinutesByAppId?.any { it.minutes > 0 } == true) {
                return@withLock PlaytimeBackfillResetResult.BLOCKED_BY_CLOUD_TRANSFER
            }

            val profile = playerProfileDao.get()
            if (profile == null || profile.historyImportRequestId != null) {
                return@withLock PlaytimeBackfillResetResult.NO_OP
            }

            transaction.run {
                gameDao.applyBackfill(gameDao.getAll().associate { it.appId to 0 })
                playerProfileDao.updatePlaytimeBackfilled(false)
                playerProfileDao.markPendingImportRecompute()
            }
            recompute()
            PlaytimeBackfillResetResult.RESET
        }
    }

    private suspend fun activeOwner(): SetupOwner? {
        if (accountChangeMarker.pendingSteamId() != null) return null
        val account = credentials.currentCredentials()?.steamId ?: return null
        return SetupOwner(account, accountChangeMarker.generation())
    }

    private fun PlayerProfile.requestFor(owner: SetupOwner): HistoryImportRequest? =
        historyImportRequestId?.takeIf { steamId == owner.steamId && historyImportGeneration == owner.generation }
            ?.let { HistoryImportRequest(it, owner) }

    private suspend fun recompute(operationId: String? = null) {
        val rules = settings.ruleConfigWithVersionFlow.first()
        gamificationUpdater.persist(
            gamificationUpdater.compute(today = time.today(), config = rules.config),
            RecomputeSource.BACKFILL,
            rules.version,
            operationId,
        )
    }
}
