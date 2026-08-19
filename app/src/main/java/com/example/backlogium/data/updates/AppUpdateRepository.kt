package com.example.backlogium.data.updates

import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

interface AppUpdateRepository {
    val state: Flow<AppUpdateState>

    suspend fun check(force: Boolean): UpdateCheckResult

    suspend fun decline(tag: String)
}

@Singleton
class DataStoreAppUpdateRepository @Inject constructor(
    private val api: GitHubReleaseApi,
    private val dataStore: UpdateDataStore,
    private val installedPackage: InstalledPackageInfoProvider,
    private val notifier: UpdateNotifier,
    private val artifactStore: UpdateArtifactStore,
    private val time: TimeProvider,
) : AppUpdateRepository {
    override val state: Flow<AppUpdateState> = dataStore.state

    override suspend fun check(force: Boolean): UpdateCheckResult {
        val before = dataStore.state.first()
        val now = time.nowMillis()
        if (!UpdateCheckPolicy.shouldRun(before.lastCheckAtMillis, now, force)) {
            return UpdateCheckResult.SkippedRecent(before.available)
        }

        artifactStore.sweep(before.available)
        val installed = try {
            installedPackage.installed()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            dataStore.recordAttempt(now)
            return UpdateCheckResult.Failed(failure, before.available)
        }

        return try {
            val response = api.latestRelease()
            val parsed = ReleaseVersion.parse(response.tagName)
            val available = response.toAvailableUpdate(installed.versionCode)
            val reason = when {
                response.draft || response.prerelease -> NoUpdateReason.DRAFT_OR_PRERELEASE
                parsed == null -> NoUpdateReason.INVALID_RELEASE
                parsed.versionCode <= installed.versionCode -> NoUpdateReason.CURRENT_VERSION
                else -> NoUpdateReason.MISSING_ASSET
            }
            dataStore.recordCheck(
                atMillis = now,
                seenTag = response.tagName.takeIf { it.isNotBlank() },
                available = available,
            )
            artifactStore.sweep(available)
            if (available == null) {
                UpdateCheckResult.NoUpdate(reason)
            } else {
                val shouldNotify = shouldNotifyForUpdate(available.tag, before.declinedTag)
                val posted = shouldNotify && notifier.notify(available)
                UpdateCheckResult.Available(available, notificationPosted = posted)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A failed request changes only the attempt timestamp; an already discovered update
            // remains available offline and can still be applied from Settings.
            dataStore.recordAttempt(now)
            UpdateCheckResult.Failed(failure, before.available)
        }
    }

    override suspend fun decline(tag: String) {
        dataStore.setDeclinedTag(tag)
    }
}
