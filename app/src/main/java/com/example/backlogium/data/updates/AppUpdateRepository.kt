package com.example.backlogium.data.updates

import com.example.backlogium.domain.TimeProvider
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
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
    private val dataStore: UpdateStateStore,
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
            val response = api.allReleases().newestAppRelease()
            if (response == null) {
                dataStore.recordCheck(
                    atMillis = now,
                    seenTag = null,
                    available = null,
                )
                artifactStore.sweep(null)
                return UpdateCheckResult.NoUpdate(NoUpdateReason.INVALID_RELEASE)
            }
            val parsed = ReleaseVersion.parse(response.tagName)
            val available = response.toAvailableUpdate(installed.versionCode)
                ?.let { enrichWithStructuredNotes(it) }
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
        dataStore.clearInstallStatus()
    }

    private suspend fun enrichWithStructuredNotes(update: AvailableUpdate): AvailableUpdate {
        val url = update.structuredNotesUrl ?: return update
        return try {
            val response = api.structuredNotes(url)
            if (!response.isSuccessful) return update
            val body = response.body() ?: return update
            if (body.contentLength() > ReleaseNotesContract.MAX_DOWNLOAD_BYTES) return update
            val raw = readBounded(body) ?: return update
            val notes = parseStructuredReleaseNotes(raw, update.tag) ?: return update
            update.copy(structuredNotes = notes)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Notes are presentation enhancement only; APK/checksum availability is unchanged.
            update
        }
    }

    private suspend fun readBounded(body: ResponseBody): String? = withContext(Dispatchers.IO) {
        body.use { responseBody ->
            responseBody.byteStream().use { input ->
                val output = ByteArrayOutputStream(ReleaseNotesContract.MAX_DOWNLOAD_BYTES)
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    if (total > ReleaseNotesContract.MAX_DOWNLOAD_BYTES) return@withContext null
                    output.write(buffer, 0, count)
                }
                output.toByteArray().toString(StandardCharsets.UTF_8)
            }
        }
    }
}

private fun Iterable<GitHubReleaseDto>.newestAppRelease(): GitHubReleaseDto? =
    asSequence()
        .filterNot { it.draft || it.prerelease }
        .mapNotNull { release ->
            ReleaseVersion.parse(release.tagName)?.let { version -> release to version }
        }
        .maxByOrNull { (_, version) -> version.versionCode }
        ?.first
