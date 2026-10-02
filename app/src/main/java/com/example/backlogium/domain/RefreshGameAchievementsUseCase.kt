package com.example.backlogium.domain

import androidx.room.withTransaction
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.repo.AchievementRepository
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.data.repo.SingleGameRefresh
import com.example.backlogium.work.SteamSyncCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject

enum class AchievementRefreshOutcome { UPDATED, NO_CHANGE, NO_USABLE_DATA, FAILED, NEEDS_CREDENTIALS, DISCARDED }

/** One game, through the shared request funnel. Fetches never hold the account-reset barrier. */
class RefreshGameAchievementsUseCase @Inject constructor(
    private val achievements: AchievementRepository,
    private val database: BacklogiumDatabase,
    private val credentials: CredentialsProvider,
    private val marker: AccountChangeMarkerStore,
    private val sync: SteamSyncCoordinator,
    private val derived: DerivedStateWriteCoordinator,
    private val updater: GamificationUpdater,
    private val settings: SettingsRepository,
    private val time: TimeProvider,
) {
    suspend operator fun invoke(appId: Long): AchievementRefreshOutcome {
        val configured = credentials.currentCredentials()
            ?.takeIf { it.apiKey.isNotBlank() && it.steamId.isNotBlank() }
            ?: return AchievementRefreshOutcome.NEEDS_CREDENTIALS
        val generation = sync.withLock {
            marker.withAccountState {
                database.withTransaction {
                    if (eligible(appId, configured.steamId)) marker.generation() else null
                }
            }
        } ?: return AchievementRefreshOutcome.DISCARDED
        return try {
            val result = achievements.refreshOne(configured.apiKey, configured.steamId, appId,
                commit = { merge ->
                    sync.withLock {
                        derived.withLock {
                            marker.withAccountState {
                                val committed = database.withTransaction {
                                    if (marker.generation() != generation || !eligible(appId, configured.steamId)) {
                                        SingleGameRefresh.Discarded
                                    } else merge()
                                }
                                if (committed is SingleGameRefresh.Persisted) {
                                    // Repeating this recompute is idempotent, and retries also finish
                                    // a derived write that failed after the raw merge committed.
                                    val rules = settings.ruleConfigWithVersion.first()
                                    updater.recompute(time.today(), RecomputeSource.SYNC, rules.config, rules.version)
                                }
                                committed
                            }
                        }
                    }
                })
            // Failure/no-data responses also belong to the initiating account and game.
            val stillEligible = sync.withLock {
                marker.withAccountState {
                    database.withTransaction {
                        marker.generation() == generation && eligible(appId, configured.steamId)
                    }
                }
            }
            if (!stillEligible) AchievementRefreshOutcome.DISCARDED else when (result) {
                is SingleGameRefresh.Persisted -> if (result.changed) AchievementRefreshOutcome.UPDATED else AchievementRefreshOutcome.NO_CHANGE
                SingleGameRefresh.NoUsableData -> AchievementRefreshOutcome.NO_USABLE_DATA
                SingleGameRefresh.Unavailable -> AchievementRefreshOutcome.FAILED
                SingleGameRefresh.Discarded -> AchievementRefreshOutcome.DISCARDED
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AchievementRefreshOutcome.FAILED
        }
    }

    private suspend fun eligible(appId: Long, steamId: String): Boolean =
        marker.pendingSteamId() == null &&
            credentials.currentCredentials()?.steamId == steamId &&
            database.playerProfileDao().get()?.steamId == steamId &&
            database.gameDao().getById(appId) != null &&
            !database.hiddenGameDao().isHidden(appId) &&
            !database.excludedSharedGameDao().isExcluded(appId)
}
