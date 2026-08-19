package com.example.backlogium.data.updates

import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import com.example.backlogium.domain.TimeProvider

class AppUpdateRepositoryTest {
    @Test
    fun failedResponsesRecordOnlyAttemptTimeAndKeepExistingOffer() = runTest {
        listOf(
            IOException("offline"),
            HttpException(Response.error<GitHubReleaseDto>(429, "rate limited".toResponseBody())),
            HttpException(Response.error<GitHubReleaseDto>(500, "server error".toResponseBody())),
        ).forEach { failure ->
            val time = FakeTime(now = 5_000L)
            val existing = release("v1.8.0").toAvailableUpdate(1L)!!
            val store = FakeUpdateStateStore(
                AppUpdateState(
                    available = existing,
                    lastCheckAtMillis = 1_000L,
                    lastSeenTag = existing.tag,
                    declinedTag = null,
                ),
            )
            val api = FakeReleaseApi().apply { this.failure = failure }
            val repository = repository(api, store, time)

            val result = repository.check(force = true)

            assertTrue(result is UpdateCheckResult.Failed)
            assertEquals(time.now, store.state.first().lastCheckAtMillis)
            assertEquals(existing, store.state.first().available)
            assertEquals(1, api.calls)
        }
    }

    @Test
    fun automaticCadenceSkipsRecentRunAndManualCheckBypassesIt() = runTest {
        val time = FakeTime(now = 100L * 60L * 60L * 1_000L)
        val store = FakeUpdateStateStore(
            AppUpdateState(lastCheckAtMillis = time.now - 19L * 60L * 60L * 1_000L),
        )
        val api = FakeReleaseApi().apply { response = release("v0.0.1") }
        val repository = repository(api, store, time)

        assertTrue(repository.check(force = false) is UpdateCheckResult.SkippedRecent)
        assertEquals(0, api.calls)

        time.now += 2L * 60L * 60L * 1_000L
        assertTrue(repository.check(force = false) is UpdateCheckResult.NoUpdate)
        assertEquals(1, api.calls)
        assertEquals(time.now, store.state.first().lastCheckAtMillis)

        time.now += 1L * 60L * 60L * 1_000L
        assertTrue(repository.check(force = true) is UpdateCheckResult.NoUpdate)
        assertEquals(2, api.calls)
        assertEquals(time.now, store.state.first().lastCheckAtMillis)
    }

    @Test
    fun declinedVersionRemainsAvailableWithoutAnotherNotification() = runTest {
        val time = FakeTime(now = 10_000L)
        val store = FakeUpdateStateStore()
        val api = FakeReleaseApi().apply { response = release("v1.8.0") }
        val notifier = RecordingNotifier()
        val repository = repository(api, store, time, notifier)

        val first = repository.check(force = true)
        repository.decline("v1.8.0")
        val second = repository.check(force = true)

        assertTrue(first is UpdateCheckResult.Available)
        assertTrue(second is UpdateCheckResult.Available)
        assertEquals(1, notifier.tags.size)
        assertEquals("v1.8.0", notifier.tags.single())
        assertFalse((second as UpdateCheckResult.Available).notificationPosted)
        assertEquals((first as UpdateCheckResult.Available).update, second.update)
    }

    private fun repository(
        api: FakeReleaseApi,
        store: FakeUpdateStateStore,
        time: FakeTime,
        notifier: RecordingNotifier = RecordingNotifier(),
    ) = DataStoreAppUpdateRepository(
        api = api,
        dataStore = store,
        installedPackage = FakeInstalledPackageInfoProvider(versionCode = 1L),
        notifier = notifier,
        artifactStore = FakeArtifactStore(),
        time = time,
    )

    private fun release(tag: String) = GitHubReleaseDto(
        tagName = tag,
        name = "Backlogium $tag",
        body = "Release notes",
        assets = listOf(
            GitHubReleaseAssetDto("app-release.apk", "https://example.test/app.apk", 10L),
            GitHubReleaseAssetDto("app-release.apk.sha256", "https://example.test/app.sha256", 64L),
        ),
    )

    private inner class FakeReleaseApi : GitHubReleaseApi {
        var calls = 0
        var response: GitHubReleaseDto = release("v1.0.0")
        var failure: Throwable? = null

        override suspend fun latestRelease(): GitHubReleaseDto {
            calls++
            failure?.let { throw it }
            return response
        }
    }

    private class FakeUpdateStateStore(initial: AppUpdateState = AppUpdateState()) : UpdateStateStore {
        private val stateFlow = MutableStateFlow(initial)
        override val state: Flow<AppUpdateState> = stateFlow

        override suspend fun recordAttempt(atMillis: Long) {
            stateFlow.value = stateFlow.value.copy(lastCheckAtMillis = atMillis)
        }

        override suspend fun recordCheck(atMillis: Long, seenTag: String?, available: AvailableUpdate?) {
            stateFlow.value = stateFlow.value.copy(
                lastCheckAtMillis = atMillis,
                lastSeenTag = seenTag,
                available = available,
            )
        }

        override suspend fun setDeclinedTag(tag: String) {
            stateFlow.value = stateFlow.value.copy(declinedTag = tag)
        }

        override suspend fun clearAvailable() {
            stateFlow.value = stateFlow.value.copy(available = null)
        }

        override suspend fun markInstallStarted(tag: String) {
            stateFlow.value = stateFlow.value.copy(installStatus = UpdateInstallStatus.Started(tag))
        }

        override suspend fun markInstallPending(tag: String) {
            stateFlow.value = stateFlow.value.copy(
                installStatus = UpdateInstallStatus.AwaitingUserAction(tag),
            )
        }

        override suspend fun markInstallFailed(tag: String, message: String) {
            stateFlow.value = stateFlow.value.copy(
                installStatus = UpdateInstallStatus.Failed(tag, message),
            )
        }

        override suspend fun clearInstallStatus() {
            stateFlow.value = stateFlow.value.copy(installStatus = UpdateInstallStatus.Idle)
        }
    }

    private class FakeInstalledPackageInfoProvider(private val versionCode: Long) :
        InstalledPackageInfoProvider {
        override fun installed() = InstalledPackageInfo("1.0.0", versionCode, setOf("signer"))
        override fun archiveSignerDigests(apk: File): Set<String>? = setOf("signer")
    }

    private class RecordingNotifier : UpdateNotifier {
        val tags = mutableListOf<String>()
        override fun notify(update: AvailableUpdate): Boolean {
            tags += update.tag
            return true
        }
    }

    private class FakeArtifactStore : UpdateArtifactStore {
        override fun artifactFile(update: AvailableUpdate) = File(update.artifactFileName)
        override fun sweep(keep: AvailableUpdate?) = Unit
        override fun delete(update: AvailableUpdate) = Unit
    }

    private class FakeTime(var now: Long) : TimeProvider {
        override fun nowMillis(): Long = now
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.of(2026, 1, 1)
    }
}
