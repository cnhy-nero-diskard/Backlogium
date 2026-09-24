package com.example.backlogium.data.repo

import com.example.backlogium.data.credentials.CloudCredentials
import com.example.backlogium.data.credentials.CloudCredentialsStore
import com.example.backlogium.data.local.dao.CloudReadDao
import com.example.backlogium.data.local.entity.CloudReadRecord
import com.example.backlogium.data.remote.CloudPresenceApi
import com.example.backlogium.data.remote.dto.CloudPresenceCurrentDto
import com.example.backlogium.data.remote.dto.CloudPresenceResponseDto
import com.example.backlogium.data.remote.dto.CloudPresenceTransitionDto
import com.example.backlogium.domain.FakeSettingsRepository
import com.example.backlogium.domain.CloudPresenceInterval
import com.example.backlogium.domain.CloudPresenceTransition
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.time.LocalDate
import java.time.ZoneId

class CloudPresenceRepositoryTest {
    @Test
    fun everyCursorAdvancingPathRetainsEvidenceBeforeItsPosition() = runBlocking {
        val api = FakeCloudPresenceApi(answer = sampleResponse(nextPosition = "2026-09-15T00:20:00Z"))
        val store = FakeCloudCredentialsStore()
        val settings = FakeSettingsRepository()
        val evidence = MemoryEvidence()
        val repo = repository(api, store, FakeCloudReadDao(), settings, ACCOUNT, evidence)

        assertEquals(CloudConfigurationResult.Saved,
            repo.verifyAndSave("https://reader.example.com/read", "secret"))
        assertEquals(1, evidence.writes)
        assertTrue(repo.read() is CloudReadResult.Success)
        assertEquals(2, evidence.writes)
        assertTrue(repo.readRemainingHistory() is CloudReadResult.Success)
        assertEquals(3, evidence.writes)
        assertTrue(repo.readCompleteHistory() is CloudReadResult.Success)
        assertEquals(4, evidence.writes)
        assertEquals("2026-09-15T00:20:00Z", settings.cloudReadPosition.first())
    }

    @Test
    fun failedEvidenceEffectLeavesPageRetryableAndRoutineResumesAfterFourPages() = runBlocking {
        val api = FakeCloudPresenceApi(answer = sampleResponse(
            nextPosition = "2026-09-15T00:20:00Z", hasMore = true,
        ))
        val store = FakeCloudCredentialsStore(CloudCredentials("https://reader.example.com/read", "secret"))
        val settings = FakeSettingsRepository()
        val evidence = MemoryEvidence()
        val repo = repository(api, store, FakeCloudReadDao(), settings, ACCOUNT, evidence)
        evidence.failNext = true
        assertEquals(
            CloudCatchUpResult.Failed(0, 0, CloudReadFailure.UNUSABLE_RESPONSE),
            repo.readRoutineCatchUp { },
        )
        assertNull(settings.cloudReadPosition.first())

        assertEquals(CloudCatchUpResult.Partial(4, 4), repo.readRoutineCatchUp { })
        assertEquals(2, api.requests.count { it.position == null })
        assertEquals("2026-09-15T00:20:00Z", settings.cloudReadPosition.first())
        api.answer = sampleResponse(nextPosition = "2026-09-15T00:30:00Z")
        assertEquals(CloudCatchUpResult.Complete(1, 1, false), repo.readRoutineCatchUp { })
    }

    @Test
    fun routineRestartContinuesAtSavedPageAndReportsNoNewTerminalData() = runBlocking {
        val api = FakeCloudPresenceApi(answer = sampleResponse(
            nextPosition = "2026-09-15T00:20:00Z", hasMore = true,
        ))
        val store = FakeCloudCredentialsStore(CloudCredentials("https://reader.example.com/read", "secret"))
        val settings = FakeSettingsRepository()
        val evidence = MemoryEvidence()
        val first = repository(api, store, FakeCloudReadDao(), settings, ACCOUNT, evidence)
        assertEquals(CloudCatchUpResult.Partial(4, 4), first.readRoutineCatchUp { })
        val savedPosition = settings.cloudReadPosition.first()
        val restarted = repository(api, store, FakeCloudReadDao(), settings, ACCOUNT, evidence)
        api.answer = sampleResponse(nextPosition = "2026-09-15T00:30:00Z").copy(
            transitions = emptyList(), current = null,
        )

        assertEquals(CloudCatchUpResult.Complete(1, 0, true), restarted.readRoutineCatchUp { })
        assertEquals(savedPosition, api.requests.last().position)
        assertEquals("2026-09-15T00:30:00Z", settings.cloudReadPosition.first())
        assertEquals(5, evidence.writes)
    }

    @Test
    fun replayAfterEvidenceAndIngestCommitButCursorFailsDoesNotDoubleCredit() = runBlocking {
        val api = FakeCloudPresenceApi(answer = sampleResponse(nextPosition = "2026-09-15T00:20:00Z"))
        val store = FakeCloudCredentialsStore(CloudCredentials("https://reader.example.com/read", "secret"))
        val settings = FakeSettingsRepository()
        val evidence = MemoryEvidence()
        val repo = repository(api, store, FakeCloudReadDao(), settings, ACCOUNT, evidence)
        val creditedPositions = mutableSetOf<String>()
        val consume: suspend (CloudPresenceSnapshot) -> Unit = { snapshot ->
            creditedPositions += snapshot.nextPosition.orEmpty()
        }
        settings.failNextCloudReadPosition = true
        try {
            repo.read(consume = consume)
            org.junit.Assert.fail("cursor write should fail after page effects")
        } catch (_: IllegalStateException) {
            // Both effects committed; the read position did not.
        }
        assertNull(settings.cloudReadPosition.first())
        assertEquals(1, evidence.writes)
        assertEquals(1, creditedPositions.size)

        assertTrue(repo.read(consume = consume) is CloudReadResult.Success)
        assertEquals(2, evidence.writes)
        assertEquals(1, creditedPositions.size)
        assertEquals("2026-09-15T00:20:00Z", settings.cloudReadPosition.first())
    }

    @Test
    fun failedReplacementEffectsKeepOldReaderCursorAndGeneration() = runBlocking {
        val api = FakeCloudPresenceApi(answer = sampleResponse(nextPosition = "2026-09-15T00:20:00Z"))
        val store = FakeCloudCredentialsStore(CloudCredentials("https://old.example.com/read", "old-secret"))
        val settings = FakeSettingsRepository()
        settings.setCloudReadPosition("old-position")
        settings.setCloudIngestPosition("old-ingest")
        val evidence = MemoryEvidence()
        val repo = repository(api, store, FakeCloudReadDao(), settings, ACCOUNT, evidence)
        evidence.failNext = true

        try {
            repo.verifyAndSave("https://new.example.com/read", "new-secret")
            org.junit.Assert.fail("evidence write should fail")
        } catch (_: IllegalStateException) {
            // Page effects failed before endpoint promotion or cursor advance.
        }

        assertEquals("old-position", settings.cloudReadPosition.first())
        assertEquals("old-ingest", settings.cloudIngestPosition.first())
        assertEquals(0L, settings.cloudReaderGeneration.first())
        assertEquals("https://old.example.com/read", store.credentials?.endpoint)
    }

    @Test
    fun unconfiguredReadDoesNotCallNetworkOrWriteDiagnostics() = runBlocking {
        val api = FakeCloudPresenceApi()
        val store = FakeCloudCredentialsStore()
        val records = FakeCloudReadDao()
        val repository = repository(api, store, records, steamId = ACCOUNT)

        assertEquals(CloudReadResult.Unconfigured, repository.read())
        assertTrue(api.requests.isEmpty())
        assertTrue(records.records.value.isEmpty())
    }

    @Test
    fun endpointValidationRejectsNonHttpsAndQueryCredentials() = runBlocking {
        assertNull(CloudPresenceRepository.normalizeEndpoint("http://reader.example.com/read"))
        assertNull(CloudPresenceRepository.normalizeEndpoint("https://user:secret@reader.example.com/read"))
        assertNull(CloudPresenceRepository.normalizeEndpoint("https://reader.example.com/read?debug=true"))
        assertEquals(
            "https://reader.example.com/read",
            CloudPresenceRepository.normalizeEndpoint(" https://reader.example.com/read/ "),
        )
    }

    @Test
    fun accountMismatchIsRejectedBeforeCredentialsAreStored() = runBlocking {
        val api = FakeCloudPresenceApi(answer = sampleResponse(account = OTHER_ACCOUNT))
        val store = FakeCloudCredentialsStore()
        val records = FakeCloudReadDao()
        val repository = repository(api, store, records, steamId = ACCOUNT)

        val result = repository.verifyAndSave("https://reader.example.com/read", "reader-secret")

        assertEquals(
            CloudConfigurationResult.AccountMismatch(ACCOUNT, OTHER_ACCOUNT),
            result,
        )
        assertNull(store.credentials)
        assertEquals(CloudReadFailure.ACCOUNT_MISMATCH.name, records.records.value.single().outcome)
        assertEquals("Bearer reader-secret", api.requests.single().authorization)
    }

    @Test
    fun successfulVerificationClearsOldPositionAndReadResumesFromNextPosition() = runBlocking {
        val api = FakeCloudPresenceApi(answer = sampleResponse(nextPosition = "2026-09-15T00:20:00Z", hasMore = true))
        val store = FakeCloudCredentialsStore()
        val records = FakeCloudReadDao()
        val settings = FakeSettingsRepository()
        settings.setCloudReadPosition("old-endpoint-position")
        val repository = repository(api, store, records, settings, ACCOUNT)

        assertEquals(
            CloudConfigurationResult.Saved,
            repository.verifyAndSave("https://reader.example.com/read/", " reader-secret "),
        )
        assertEquals(null, api.requests[0].position)
        assertEquals(CloudCredentials("https://reader.example.com/read", "reader-secret"), store.credentials)
        assertEquals("2026-09-15T00:20:00Z", settings.cloudReadPosition.first())
        // An incomplete page carries no trailing interval: the latest current state sits
        // beyond omitted transitions, so combining them would fabricate a tail across the gap.
        assertEquals(0, repository.snapshot.first()!!.intervals.size)
        assertEquals(true, repository.snapshot.first()!!.hasMore)

        api.answer = sampleResponse(nextPosition = "2026-09-15T00:30:00Z")
        assertTrue(repository.read() is CloudReadResult.Success)
        assertEquals("2026-09-15T00:20:00Z", api.requests[1].position)
        assertEquals("2026-09-15T00:30:00Z", settings.cloudReadPosition.first())
        assertEquals(2, records.records.value.size)
    }

    @Test
    fun removingConfigurationClearsCredentialAndPositionButKeepsAuditRecords() = runBlocking {
        val api = FakeCloudPresenceApi(answer = sampleResponse(nextPosition = "2026-09-15T00:20:00Z"))
        val store = FakeCloudCredentialsStore()
        val records = FakeCloudReadDao()
        val settings = FakeSettingsRepository()
        val repository = repository(api, store, records, settings, ACCOUNT)

        assertEquals(CloudConfigurationResult.Saved, repository.verifyAndSave("https://reader.example.com/read", "secret"))
        repository.removeConfiguration()

        assertNull(store.credentials)
        assertNull(settings.cloudReadPosition.first())
        assertEquals(1, records.records.value.size)
        assertNull(repository.snapshot.first())
    }

    @Test
    fun malformedSuccessIsReportedAsUnusableAndDoesNotStoreCredentials() = runBlocking {
        val api = FakeCloudPresenceApi(answer = CloudPresenceResponseDto(account = ACCOUNT))
        val store = FakeCloudCredentialsStore()
        val records = FakeCloudReadDao()
        val repository = repository(api, store, records, steamId = ACCOUNT)

        assertEquals(
            CloudConfigurationResult.UnusableResponse,
            repository.verifyAndSave("https://reader.example.com/read", "reader-secret"),
        )
        assertNull(store.credentials)
        assertEquals(CloudReadFailure.UNUSABLE_RESPONSE.name, records.records.value.single().outcome)
    }

    @Test
    fun incompletePageIsBoundedAndDoesNotFabricateTailAcrossOmittedTransitions() = runBlocking {
        val pageEnd = "2026-09-15T00:10:00Z"
        val api = FakeCloudPresenceApi(
            answer = CloudPresenceResponseDto(
                account = ACCOUNT,
                transitions = listOf(
                    CloudPresenceTransitionDto(
                        v = 3,
                        t = "2026-09-15T00:00:00Z",
                        gameid = "10",
                        gameName = "Portal",
                        personastate = 1,
                    ),
                    CloudPresenceTransitionDto(
                        v = 3,
                        t = pageEnd,
                        prevLastObservedAt = "2026-09-15T00:09:00Z",
                        gameid = "20",
                        gameName = "Other",
                        personastate = 1,
                    ),
                ),
                current = CloudPresenceCurrentDto(
                    v = 2,
                    lastObservedAt = "2026-09-15T01:00:00Z",
                    gameid = "20",
                    gameName = "Other",
                    personastate = 1,
                ),
                nextPosition = pageEnd,
                hasMore = true,
                windowStart = "2026-09-15T00:00:00Z",
                windowEnd = "2026-09-15T01:00:00Z",
                readAt = "2026-09-15T01:01:00Z",
            ),
        )
        val store = FakeCloudCredentialsStore(
            CloudCredentials("https://reader.example.com/read", "secret"),
        )
        val records = FakeCloudReadDao()
        val repository = repository(api, store, records, steamId = ACCOUNT)

        val result = repository.read()
        assertTrue(result is CloudReadResult.Success)
        val snapshot = (result as CloudReadResult.Success).snapshot

        assertTrue(snapshot.hasMore)
        // The page ends at its last returned transition, not at the latest observation:
        // an incomplete page must not masquerade as full-window evidence.
        assertEquals(
            java.time.Instant.parse(pageEnd).toEpochMilli(),
            snapshot.windowEnd,
        )
        // The latest current state sits beyond omitted transitions, so no trailing
        // interval may be fabricated across them: only the closed page interval survives.
        assertEquals(1, snapshot.intervals.size)
        assertEquals(10L, snapshot.intervals.single().appId)
        assertTrue(snapshot.intervals.none { it.ongoing })
    }

    @Test
    fun paginatedReadsPreserveBoundaryIntervalViaOverlap() = runBlocking {
        val first = "2026-09-15T00:00:00Z"
        val boundary = "2026-09-15T00:10:00Z"
        val last = "2026-09-15T00:20:00Z"
        val api = FakeCloudPresenceApi(
            answer = CloudPresenceResponseDto(
                account = ACCOUNT,
                transitions = listOf(
                    CloudPresenceTransitionDto(
                        v = 3,
                        t = first,
                        gameid = "10",
                        gameName = "Portal",
                        personastate = 1,
                    ),
                    CloudPresenceTransitionDto(
                        v = 3,
                        t = boundary,
                        prevLastObservedAt = "2026-09-15T00:09:00Z",
                        gameid = "20",
                        gameName = "Other",
                        personastate = 1,
                    ),
                ),
                current = CloudPresenceCurrentDto(
                    v = 2,
                    lastObservedAt = "2026-09-15T01:00:00Z",
                    gameid = "20",
                    gameName = "Other",
                    personastate = 1,
                ),
                nextPosition = boundary,
                hasMore = true,
                windowStart = first,
                windowEnd = boundary,
                readAt = "2026-09-15T00:11:00Z",
            ),
        )
        val store = FakeCloudCredentialsStore(
            CloudCredentials("https://reader.example.com/read", "secret"),
        )
        val records = FakeCloudReadDao()
        val settings = FakeSettingsRepository()
        val repository = repository(api, store, records, settings, ACCOUNT)

        val firstResult = repository.read()
        assertTrue(firstResult is CloudReadResult.Success)
        // The resume watermark backs up one transition so the boundary transition is
        // returned again: without overlap the t250 -> t251 interval would be lost.
        assertEquals(first, settings.cloudReadPosition.first())
        assertEquals(first, (firstResult as CloudReadResult.Success).snapshot.nextPosition)
        assertEquals(1, firstResult.snapshot.intervals.size)

        api.answer = CloudPresenceResponseDto(
            account = ACCOUNT,
            transitions = listOf(
                CloudPresenceTransitionDto(
                    v = 3,
                    t = boundary,
                    prevLastObservedAt = "2026-09-15T00:09:00Z",
                    gameid = "20",
                    gameName = "Other",
                    personastate = 1,
                ),
                CloudPresenceTransitionDto(
                    v = 3,
                    t = last,
                    prevLastObservedAt = "2026-09-15T00:19:00Z",
                    gameid = "30",
                    gameName = "Third",
                    personastate = 1,
                ),
            ),
            current = null,
            nextPosition = last,
            hasMore = false,
            windowStart = first,
            windowEnd = last,
            readAt = "2026-09-15T00:21:00Z",
        )

        val secondResult = repository.read()
        assertTrue(secondResult is CloudReadResult.Success)
        assertEquals(first, api.requests[1].position)
        val second = (secondResult as CloudReadResult.Success).snapshot
        val boundaryInterval = second.intervals.singleOrNull {
            it.startAt == java.time.Instant.parse(boundary).toEpochMilli()
        }
        assertTrue(boundaryInterval != null)
        assertEquals(20L, boundaryInterval!!.appId)
        assertEquals(java.time.Instant.parse(last).toEpochMilli(), boundaryInterval.endAt)
        assertEquals(last, settings.cloudReadPosition.first())
    }

    @Test
    fun readCompleteHistoryAccumulatesOwnedIntervalsAcrossPages() = runBlocking {
        val first = "2026-09-15T00:00:00Z"
        val boundary = "2026-09-15T00:10:00Z"
        val last = "2026-09-15T00:20:00Z"
        val page1 = CloudPresenceResponseDto(
            account = ACCOUNT,
            transitions = listOf(
                CloudPresenceTransitionDto(
                    v = 3,
                    t = first,
                    gameid = "10",
                    gameName = "Portal",
                    personastate = 1,
                ),
                CloudPresenceTransitionDto(
                    v = 3,
                    t = boundary,
                    prevLastObservedAt = "2026-09-15T00:09:00Z",
                    gameid = "20",
                    gameName = "Other",
                    personastate = 1,
                ),
            ),
            current = CloudPresenceCurrentDto(
                v = 2,
                lastObservedAt = "2026-09-15T01:00:00Z",
                gameid = "20",
                gameName = "Other",
                personastate = 1,
            ),
            nextPosition = boundary,
            hasMore = true,
            windowStart = first,
            windowEnd = boundary,
            readAt = "2026-09-15T00:11:00Z",
        )
        val page2 = CloudPresenceResponseDto(
            account = ACCOUNT,
            transitions = listOf(
                CloudPresenceTransitionDto(
                    v = 3,
                    t = boundary,
                    prevLastObservedAt = "2026-09-15T00:09:00Z",
                    gameid = "20",
                    gameName = "Other",
                    personastate = 1,
                ),
                CloudPresenceTransitionDto(
                    v = 3,
                    t = last,
                    prevLastObservedAt = "2026-09-15T00:19:00Z",
                    gameid = "30",
                    gameName = "Third",
                    personastate = 1,
                ),
            ),
            current = null,
            nextPosition = last,
            hasMore = false,
            windowStart = first,
            windowEnd = last,
            readAt = "2026-09-15T00:21:00Z",
        )
        val requests = mutableListOf<String?>()
        val api = object : CloudPresenceApi {
            override suspend fun read(
                endpoint: String,
                authorization: String,
                position: String?,
            ): CloudPresenceResponseDto {
                requests += position
                return if (position == null) page1 else page2
            }
        }
        val store = FakeCloudCredentialsStore(
            CloudCredentials("https://reader.example.com/read", "secret"),
        )
        val records = FakeCloudReadDao()
        val settings = FakeSettingsRepository()
        // Simulate prior single-page reads that advanced the cursor past the first page:
        // its owned intervals were discarded by the per-page consumer, and only the final
        // page would remain for a one-shot apply. The drain must restart from the
        // beginning so the complete history reaches the re-file.
        settings.setCloudReadPosition(first)
        val repository = repository(api, store, records, settings, ACCOUNT)

        val consumed = mutableListOf<CloudPresenceSnapshot>()
        val result = repository.readCompleteHistory(consume = { consumed += it })

        assertTrue(result is CloudReadResult.Success)
        val combined = (result as CloudReadResult.Success).snapshot
        assertFalse(combined.hasMore)
        // Both pages' intervals survive: a terminal-page-only apply would hold just the
        // boundary interval and mark the sweep applied with the first page's rows missing.
        assertEquals(listOf(10L, 20L), combined.intervals.map { it.appId })
        assertEquals(4, combined.observationCount)
        assertEquals(2, consumed.size)
        assertEquals(2, requests.size)
        assertNull(requests[0])
        assertEquals(last, settings.cloudReadPosition.first())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun drainHoldsWatermarkAgainstConcurrentPlacementRead() = runTest {
        val first = "2026-09-15T00:00:00Z"
        val boundary = "2026-09-15T00:10:00Z"
        val last = "2026-09-15T00:20:00Z"
        val page1 = CloudPresenceResponseDto(
            account = ACCOUNT,
            transitions = listOf(
                CloudPresenceTransitionDto(
                    v = 3,
                    t = first,
                    gameid = "10",
                    gameName = "Portal",
                    personastate = 1,
                ),
                CloudPresenceTransitionDto(
                    v = 3,
                    t = boundary,
                    prevLastObservedAt = "2026-09-15T00:09:00Z",
                    gameid = "20",
                    gameName = "Other",
                    personastate = 1,
                ),
            ),
            current = CloudPresenceCurrentDto(
                v = 2,
                lastObservedAt = "2026-09-15T01:00:00Z",
                gameid = "20",
                gameName = "Other",
                personastate = 1,
            ),
            nextPosition = boundary,
            hasMore = true,
            windowStart = first,
            windowEnd = boundary,
            readAt = "2026-09-15T00:11:00Z",
        )
        val page2 = CloudPresenceResponseDto(
            account = ACCOUNT,
            transitions = listOf(
                CloudPresenceTransitionDto(
                    v = 3,
                    t = boundary,
                    prevLastObservedAt = "2026-09-15T00:09:00Z",
                    gameid = "20",
                    gameName = "Other",
                    personastate = 1,
                ),
                CloudPresenceTransitionDto(
                    v = 3,
                    t = last,
                    prevLastObservedAt = "2026-09-15T00:19:00Z",
                    gameid = "30",
                    gameName = "Third",
                    personastate = 1,
                ),
            ),
            current = null,
            nextPosition = last,
            hasMore = false,
            windowStart = first,
            windowEnd = last,
            readAt = "2026-09-15T00:21:00Z",
        )
        // Terminal window after the drain: no new transitions, so a post-drain placement
        // read advances nothing and consumes nothing.
        val terminal = CloudPresenceResponseDto(
            account = ACCOUNT,
            transitions = emptyList(),
            current = null,
            nextPosition = last,
            hasMore = false,
            windowStart = first,
            windowEnd = last,
            readAt = "2026-09-15T00:22:00Z",
        )
        val requests = mutableListOf<String?>()
        val enteredSecondPage = CompletableDeferred<Unit>()
        val releaseSecondPage = CompletableDeferred<Unit>()
        val api = object : CloudPresenceApi {
            override suspend fun read(
                endpoint: String,
                authorization: String,
                position: String?,
            ): CloudPresenceResponseDto {
                requests += position
                return when (position) {
                    null -> page1
                    first -> {
                        enteredSecondPage.complete(Unit)
                        releaseSecondPage.await()
                        page2
                    }
                    last -> terminal
                    else -> error("Unexpected cloud position $position")
                }
            }
        }
        val store = FakeCloudCredentialsStore(
            CloudCredentials("https://reader.example.com/read", "secret"),
        )
        val records = FakeCloudReadDao()
        val settings = FakeSettingsRepository()
        settings.setCloudReadPosition(first)
        val repository = repository(api, store, records, settings, ACCOUNT)

        val drain = async { repository.readCompleteHistory() }
        enteredSecondPage.await()

        // A worker placement read (Steam sync/post-play) attempting between drain pages
        // must wait for the drain's terminal watermark rather than consuming page 2 and
        // advancing the shared cursor past a page the drain then skips.
        val placement = async { repository.read(trigger = CloudReadTrigger.SYNC) }
        runCurrent()
        assertEquals(listOf(null, first), requests)

        releaseSecondPage.complete(Unit)
        val drainResult = drain.await()
        val placementResult = placement.await()

        assertTrue(drainResult is CloudReadResult.Success)
        val combined = (drainResult as CloudReadResult.Success).snapshot
        assertFalse(combined.hasMore)
        assertEquals(listOf(10L, 20L), combined.intervals.map { it.appId })
        assertEquals(4, combined.observationCount)
        // The placement read ran after the drain and observed only the terminal window:
        // a single request for the second page exists (the drain's), never a stolen copy.
        assertEquals(listOf(null, first, last), requests)
        assertEquals(1, requests.count { it == first })
        assertTrue(placementResult is CloudReadResult.Success)
        assertEquals(0, (placementResult as CloudReadResult.Success).snapshot.observationCount)
        assertEquals(last, settings.cloudReadPosition.first())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun verificationWaitsForDrainBetweenPages() = runTest {
        val oldEndpoint = "https://reader.example.com/read"
        val newEndpoint = "https://new-reader.example.com/read"
        val first = "2026-09-15T00:00:00Z"
        val boundary = "2026-09-15T00:10:00Z"
        val last = "2026-09-15T00:20:00Z"
        val verifiedPosition = "2026-09-15T00:25:00Z"
        val page1 = CloudPresenceResponseDto(
            account = ACCOUNT,
            transitions = listOf(
                CloudPresenceTransitionDto(
                    v = 3,
                    t = first,
                    gameid = "10",
                    gameName = "Portal",
                    personastate = 1,
                ),
                CloudPresenceTransitionDto(
                    v = 3,
                    t = boundary,
                    prevLastObservedAt = "2026-09-15T00:09:00Z",
                    gameid = "20",
                    gameName = "Other",
                    personastate = 1,
                ),
            ),
            current = CloudPresenceCurrentDto(
                v = 2,
                lastObservedAt = "2026-09-15T01:00:00Z",
                gameid = "20",
                gameName = "Other",
                personastate = 1,
            ),
            nextPosition = boundary,
            hasMore = true,
            windowStart = first,
            windowEnd = boundary,
            readAt = "2026-09-15T00:11:00Z",
        )
        val page2 = CloudPresenceResponseDto(
            account = ACCOUNT,
            transitions = listOf(
                CloudPresenceTransitionDto(
                    v = 3,
                    t = boundary,
                    prevLastObservedAt = "2026-09-15T00:09:00Z",
                    gameid = "20",
                    gameName = "Other",
                    personastate = 1,
                ),
                CloudPresenceTransitionDto(
                    v = 3,
                    t = last,
                    prevLastObservedAt = "2026-09-15T00:19:00Z",
                    gameid = "30",
                    gameName = "Third",
                    personastate = 1,
                ),
            ),
            current = null,
            nextPosition = last,
            hasMore = false,
            windowStart = first,
            windowEnd = last,
            readAt = "2026-09-15T00:21:00Z",
        )
        val verificationPage = CloudPresenceResponseDto(
            account = ACCOUNT,
            transitions = listOf(
                CloudPresenceTransitionDto(
                    v = 2,
                    t = "2026-09-15T00:05:00Z",
                    gameid = "99",
                    gameName = "Verified",
                    personastate = 1,
                ),
            ),
            current = CloudPresenceCurrentDto(
                v = 2,
                lastObservedAt = "2026-09-15T00:06:00Z",
                gameid = "99",
                gameName = "Verified",
                personastate = 1,
            ),
            nextPosition = verifiedPosition,
            hasMore = false,
            windowStart = first,
            windowEnd = "2026-09-15T00:06:00Z",
            readAt = "2026-09-15T00:26:00Z",
        )
        val requests = mutableListOf<Pair<String, String?>>()
        val enteredSecondPage = CompletableDeferred<Unit>()
        val releaseSecondPage = CompletableDeferred<Unit>()
        val api = object : CloudPresenceApi {
            override suspend fun read(
                endpoint: String,
                authorization: String,
                position: String?,
            ): CloudPresenceResponseDto {
                requests += endpoint to position
                if (endpoint == newEndpoint) return verificationPage
                return when (position) {
                    null -> page1
                    first -> {
                        enteredSecondPage.complete(Unit)
                        releaseSecondPage.await()
                        page2
                    }
                    else -> error("Unexpected cloud position $position")
                }
            }
        }
        val store = FakeCloudCredentialsStore(
            CloudCredentials(oldEndpoint, "secret"),
        )
        val records = FakeCloudReadDao()
        val settings = FakeSettingsRepository()
        settings.setCloudReadPosition(first)
        val repository = repository(api, store, records, settings, ACCOUNT)

        val drain = async { repository.readCompleteHistory() }
        enteredSecondPage.await()

        // A Settings verification completing between drain pages clears both positions and
        // persists its own nextPosition: without the shared read-sequence boundary the
        // drain's next page would resume from the new sequence while its accumulator still
        // holds the old sequence's first page. Promotion must wait for the terminal
        // watermark instead, so the drain stays within one generation.
        val verification = async { repository.verifyAndSave(newEndpoint, "new-secret") }
        runCurrent()
        assertFalse(verification.isCompleted)

        releaseSecondPage.complete(Unit)
        val drainResult = drain.await()
        val verificationResult = verification.await()

        assertEquals(CloudConfigurationResult.Saved, verificationResult)
        assertTrue(drainResult is CloudReadResult.Success)
        val combined = (drainResult as CloudReadResult.Success).snapshot
        assertFalse(combined.hasMore)
        assertEquals(listOf(10L, 20L), combined.intervals.map { it.appId })
        assertEquals(4, combined.observationCount)
        // The drain never mixed the verification generation into its accumulator, and its
        // terminal watermark did not overwrite the promoted one: verification is last writer.
        assertEquals(CloudCredentials(newEndpoint, "new-secret"), store.credentials)
        assertEquals(verifiedPosition, settings.cloudReadPosition.first())
        assertEquals(
            listOf(oldEndpoint to null, oldEndpoint to first, newEndpoint to null),
            requests,
        )
    }

    @Test
    fun httpUnusableResponseIsMappedToUnusableResponse() = runBlocking {
        val api = FakeCloudPresenceApi()
        api.failure = HttpException(
            Response.error<CloudPresenceResponseDto>(
                500,
                "{\"error\":\"unusable_response\"}".toResponseBody("application/json".toMediaType()),
            ),
        )
        val store = FakeCloudCredentialsStore(
            CloudCredentials("https://reader.example.com/read", "secret"),
        )
        val records = FakeCloudReadDao()
        val repository = repository(api, store, records, steamId = ACCOUNT)

        val result = repository.read()

        assertEquals(CloudReadResult.Failed(CloudReadFailure.UNUSABLE_RESPONSE), result)
        assertEquals(CloudReadFailure.UNUSABLE_RESPONSE.name, records.records.value.single().outcome)
    }

    @Test
    fun httpServerErrorWithoutControlledBodyRemainsUnreachable() = runBlocking {
        val api = FakeCloudPresenceApi()
        api.failure = HttpException(
            Response.error<CloudPresenceResponseDto>(
                500,
                "boom".toResponseBody("text/plain".toMediaType()),
            ),
        )
        val store = FakeCloudCredentialsStore(
            CloudCredentials("https://reader.example.com/read", "secret"),
        )
        val records = FakeCloudReadDao()
        val repository = repository(api, store, records, steamId = ACCOUNT)

        val result = repository.read()

        assertEquals(CloudReadResult.Failed(CloudReadFailure.UNREACHABLE), result)
        assertEquals(CloudReadFailure.UNREACHABLE.name, records.records.value.single().outcome)
    }

    @Test
    fun successfulReadNeverRewritesEncryptedCredentials() = runBlocking {
        val api = FakeCloudPresenceApi(answer = sampleResponse(nextPosition = "2026-09-15T00:20:00Z"))
        val store = FakeCloudCredentialsStore(
            CloudCredentials("https://reader.example.com/read", "secret"),
        )
        val records = FakeCloudReadDao()
        val settings = FakeSettingsRepository()
        val repository = repository(api, store, records, settings, ACCOUNT)

        assertTrue(repository.read() is CloudReadResult.Success)

        // A normal read's only durable effects are the watermark and its audit record:
        // rewriting the encrypted credentials would produce fresh AES-GCM ciphertext per
        // fetch and turn a Keystore/DataStore failure into a failed network read.
        assertEquals(0, store.writeCount)
        assertEquals(
            CloudCredentials("https://reader.example.com/read", "secret"),
            store.credentials,
        )
        assertEquals("2026-09-15T00:20:00Z", settings.cloudReadPosition.first())
        assertEquals(1, records.records.value.size)
    }

    @Test
    fun accountChangeInvalidationDropsSnapshotAndHealth() = runBlocking {
        val api = FakeCloudPresenceApi(answer = sampleResponse(nextPosition = "2026-09-15T00:20:00Z"))
        val store = FakeCloudCredentialsStore(
            CloudCredentials("https://reader.example.com/read", "secret"),
        )
        val records = FakeCloudReadDao()
        val settings = FakeSettingsRepository()
        val repository = repository(api, store, records, settings, ACCOUNT)

        assertTrue(repository.read() is CloudReadResult.Success)
        // Prime the in-memory configuration the same way a Settings collector would, so the
        // post-switch assertions distinguish "old health" from "never configured".
        repository.refreshConfiguration()
        assertTrue(repository.snapshot.first() != null)
        assertEquals(true, repository.status.first().healthy)

        // Account-change effects: the durable position and audit rows are cleared by
        // SettingsDataStore.clearAccountDerivedState and AccountRoomReset, and the
        // same-process snapshot is dropped by invalidateForAccountChange.
        settings.clearCloudReadPosition()
        settings.setCloudIngestPosition(1000L.toString())
        records.deleteAll()
        repository.invalidateForAccountChange()

        assertNull(repository.snapshot.first())
        val status = repository.status.first()
        assertEquals(true, status.configured)
        assertNull(status.healthy)
        assertNull(status.lastSuccessAt)
        assertNull(status.lastAttemptAt)
        assertNull(settings.cloudReadPosition.first())
        assertNull(settings.cloudIngestPosition.first())
    }

    @Test
    fun inFlightReadIsDiscardedWhenAccountChanges() = runTest {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<CloudPresenceResponseDto>()
        val api = DeferredCloudPresenceApi(entered, gate)
        val store = FakeCloudCredentialsStore(
            CloudCredentials("https://reader.example.com/read", "secret"),
        )
        val records = FakeCloudReadDao()
        val settings = FakeSettingsRepository()
        val credentials = MutableFakeCredentials(ACCOUNT)
        val repository = CloudPresenceRepository(
            api = api,
            credentialsStore = store,
            credentialsProvider = credentials,
            settings = settings,
            cloudReadDao = records,
            time = FixedTimeProvider(),
        )

        val pending = async { repository.read() }
        entered.await()

        // A->B switch while A's response is still in flight: durable state is cleared and
        // the generation is bumped before A's response is released.
        settings.clearCloudReadPosition()
        records.deleteAll()
        repository.invalidateForAccountChange()
        credentials.steamId = OTHER_ACCOUNT
        gate.complete(sampleResponse(nextPosition = "2026-09-15T00:20:00Z"))

        assertEquals(CloudReadResult.Failed(CloudReadFailure.ACCOUNT_MISMATCH), pending.await())
        assertNull(repository.snapshot.first())
        assertNull(settings.cloudReadPosition.first())
        assertTrue(records.records.value.isEmpty())
        assertEquals(0, store.writeCount)
    }

    @Test
    fun inFlightVerificationIsDiscardedWhenAccountChanges() = runTest {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<CloudPresenceResponseDto>()
        val api = DeferredCloudPresenceApi(entered, gate)
        val store = FakeCloudCredentialsStore(credentials = null)
        val records = FakeCloudReadDao()
        val settings = FakeSettingsRepository()
        val credentials = MutableFakeCredentials(ACCOUNT)
        val repository = CloudPresenceRepository(
            api = api,
            credentialsStore = store,
            credentialsProvider = credentials,
            settings = settings,
            cloudReadDao = records,
            time = FixedTimeProvider(),
        )

        val pending = async {
            repository.verifyAndSave("https://reader.example.com/read", "reader-secret")
        }
        entered.await()

        // A->B switch while A's verification is still in flight.
        settings.clearCloudReadPosition()
        records.deleteAll()
        repository.invalidateForAccountChange()
        credentials.steamId = OTHER_ACCOUNT
        gate.complete(sampleResponse(nextPosition = "2026-09-15T00:20:00Z"))

        val result = pending.await()
        assertEquals(
            CloudConfigurationResult.AccountMismatch(OTHER_ACCOUNT, ACCOUNT),
            result,
        )
        assertNull(store.credentials)
        assertNull(repository.snapshot.first())
        assertNull(settings.cloudReadPosition.first())
        assertTrue(records.records.value.isEmpty())
    }

    @Test
    fun verificationDoesNotPersistWhenInvalidationLandsBetweenAccountAndGenerationReads() = runTest {
        val accountEntered = CompletableDeferred<Unit>()
        val accountGate = CompletableDeferred<Unit>()
        val apiEntered = CompletableDeferred<Unit>()
        val apiGate = CompletableDeferred<CloudPresenceResponseDto>()
        val api = DeferredCloudPresenceApi(apiEntered, apiGate)
        val store = FakeCloudCredentialsStore(credentials = null)
        val records = FakeCloudReadDao()
        val settings = FakeSettingsRepository()
        val credentials = GatingCredentialsProvider(ACCOUNT, accountEntered, accountGate)
        val repository = CloudPresenceRepository(
            api = api,
            credentialsStore = store,
            credentialsProvider = credentials,
            settings = settings,
            cloudReadDao = records,
            time = FixedTimeProvider(),
        )

        val pending = async {
            repository.verifyAndSave("https://reader.example.com/read", "reader-secret")
        }
        accountEntered.await()

        // A->B invalidation landing between the account read and the generation sampling.
        // Sampling them separately would capture old account A with B's new generation, so
        // A's late response would later see an unchanged generation and be accepted.
        val invalidation = async {
            settings.clearCloudReadPosition()
            records.deleteAll()
            repository.invalidateForAccountChange()
        }
        accountGate.complete(Unit)
        apiEntered.await()
        invalidation.await()
        credentials.steamId = OTHER_ACCOUNT
        apiGate.complete(sampleResponse(nextPosition = "2026-09-15T00:20:00Z"))

        assertEquals(
            CloudConfigurationResult.AccountMismatch(OTHER_ACCOUNT, ACCOUNT),
            pending.await(),
        )
        assertNull(store.credentials)
        assertNull(repository.snapshot.first())
        assertNull(settings.cloudReadPosition.first())
        assertTrue(records.records.value.isEmpty())
    }

    @Test
    fun readDoesNotPersistWhenInvalidationLandsBetweenAccountAndGenerationReads() = runTest {
        val accountEntered = CompletableDeferred<Unit>()
        val accountGate = CompletableDeferred<Unit>()
        val apiEntered = CompletableDeferred<Unit>()
        val apiGate = CompletableDeferred<CloudPresenceResponseDto>()
        val api = DeferredCloudPresenceApi(apiEntered, apiGate)
        val store = FakeCloudCredentialsStore(
            CloudCredentials("https://reader.example.com/read", "secret"),
        )
        val records = FakeCloudReadDao()
        val settings = FakeSettingsRepository()
        settings.setCloudReadPosition("2026-09-15T00:05:00Z")
        val credentials = GatingCredentialsProvider(ACCOUNT, accountEntered, accountGate)
        val repository = CloudPresenceRepository(
            api = api,
            credentialsStore = store,
            credentialsProvider = credentials,
            settings = settings,
            cloudReadDao = records,
            time = FixedTimeProvider(),
        )

        val pending = async { repository.read() }
        accountEntered.await()

        // Same boundary across the watermark read: the stored endpoint, the Steam account,
        // and the resumable position must be captured atomically with the generation.
        val invalidation = async {
            settings.clearCloudReadPosition()
            records.deleteAll()
            repository.invalidateForAccountChange()
        }
        accountGate.complete(Unit)
        apiEntered.await()
        invalidation.await()
        credentials.steamId = OTHER_ACCOUNT
        apiGate.complete(sampleResponse(nextPosition = "2026-09-15T00:20:00Z"))

        assertEquals(CloudReadResult.Failed(CloudReadFailure.ACCOUNT_MISMATCH), pending.await())
        assertNull(repository.snapshot.first())
        assertNull(settings.cloudReadPosition.first())
        assertTrue(records.records.value.isEmpty())
        assertEquals(0, store.writeCount)
    }

    @Test
    fun readStartedAfterInvalidationButBeforePromotionDoesNotLeak() = runTest {
        val apiEntered = CompletableDeferred<Unit>()
        val apiGate = CompletableDeferred<CloudPresenceResponseDto>()
        val api = DeferredCloudPresenceApi(apiEntered, apiGate)
        val store = FakeCloudCredentialsStore(
            CloudCredentials("https://reader.example.com/read", "secret"),
        )
        val records = FakeCloudReadDao()
        val settings = FakeSettingsRepository()
        val credentials = MutableFakeCredentials(ACCOUNT)
        val repository = CloudPresenceRepository(
            api = api,
            credentialsStore = store,
            credentialsProvider = credentials,
            settings = settings,
            cloudReadDao = records,
            time = FixedTimeProvider(),
        )

        // Coordinator's reset runs while the old Steam identity is still committed: durable
        // state is cleared and the generation bumped, but the provider still reports A.
        settings.clearCloudReadPosition()
        records.deleteAll()
        repository.invalidateForAccountChange()

        // A cloud read starting in that window captures old account A with the new
        // generation, so the generation alone still matches after promotion to B.
        val pending = async { repository.read() }
        apiEntered.await()
        credentials.steamId = OTHER_ACCOUNT
        apiGate.complete(sampleResponse(nextPosition = "2026-09-15T00:20:00Z"))

        assertEquals(CloudReadResult.Failed(CloudReadFailure.ACCOUNT_MISMATCH), pending.await())
        assertNull(repository.snapshot.first())
        assertNull(settings.cloudReadPosition.first())
        assertTrue(records.records.value.isEmpty())
        assertEquals(0, store.writeCount)
    }

    @Test
    fun verificationStartedAfterInvalidationButBeforePromotionDoesNotLeak() = runTest {
        val apiEntered = CompletableDeferred<Unit>()
        val apiGate = CompletableDeferred<CloudPresenceResponseDto>()
        val api = DeferredCloudPresenceApi(apiEntered, apiGate)
        val store = FakeCloudCredentialsStore(credentials = null)
        val records = FakeCloudReadDao()
        val settings = FakeSettingsRepository()
        val credentials = MutableFakeCredentials(ACCOUNT)
        val repository = CloudPresenceRepository(
            api = api,
            credentialsStore = store,
            credentialsProvider = credentials,
            settings = settings,
            cloudReadDao = records,
            time = FixedTimeProvider(),
        )

        settings.clearCloudReadPosition()
        records.deleteAll()
        repository.invalidateForAccountChange()

        val pending = async {
            repository.verifyAndSave("https://reader.example.com/read", "reader-secret")
        }
        apiEntered.await()
        credentials.steamId = OTHER_ACCOUNT
        apiGate.complete(sampleResponse(nextPosition = "2026-09-15T00:20:00Z"))

        assertEquals(
            CloudConfigurationResult.AccountMismatch(OTHER_ACCOUNT, ACCOUNT),
            pending.await(),
        )
        assertNull(store.credentials)
        assertNull(repository.snapshot.first())
        assertNull(settings.cloudReadPosition.first())
        assertTrue(records.records.value.isEmpty())
    }

    private fun repository(
        api: CloudPresenceApi,
        store: CloudCredentialsStore,
        records: FakeCloudReadDao,
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        steamId: String?,
        pending: CloudPendingEvidence? = null,
    ): CloudPresenceRepository = CloudPresenceRepository(
        api = api,
        credentialsStore = store,
        credentialsProvider = FakeCredentials(steamId),
        settings = settings,
        cloudReadDao = records,
        pendingEvidence = pending ?: MemoryEvidence(),
        time = FixedTimeProvider(),
    )

    private class MemoryEvidence : CloudPendingEvidence {
        var writes = 0
        var failNext = false
        override suspend fun boundary(account: String, generation: Long): CloudPresenceTransition? = null
        override suspend fun retain(
            account: String, generation: Long, windowStart: Long,
            intervals: List<CloudPresenceInterval>, lastTransition: CloudPresenceTransition?,
        ) {
            if (failNext) {
                failNext = false
                error("simulated evidence write failure")
            }
            writes++
        }
        override suspend fun intervals(account: String, generation: Long): List<CloudPresenceInterval> = emptyList()
        override suspend fun earliestWindowStart(account: String, generation: Long): Long? = null
        override suspend fun clear() = Unit
        override suspend fun clearExcept(account: String, generation: Long) = Unit
    }

    private class FakeCloudPresenceApi(
        var answer: CloudPresenceResponseDto = sampleResponse(),
        var failure: Throwable? = null,
    ) : CloudPresenceApi {
        data class Request(val endpoint: String, val authorization: String, val position: String?)

        val requests = mutableListOf<Request>()

        override suspend fun read(
            endpoint: String,
            authorization: String,
            position: String?,
        ): CloudPresenceResponseDto {
            requests += Request(endpoint, authorization, position)
            failure?.let { throw it }
            return answer
        }
    }

    private class DeferredCloudPresenceApi(
        private val entered: CompletableDeferred<Unit>,
        private val gate: CompletableDeferred<CloudPresenceResponseDto>,
    ) : CloudPresenceApi {
        val requests = mutableListOf<FakeCloudPresenceApi.Request>()

        override suspend fun read(
            endpoint: String,
            authorization: String,
            position: String?,
        ): CloudPresenceResponseDto {
            requests += FakeCloudPresenceApi.Request(endpoint, authorization, position)
            entered.complete(Unit)
            return gate.await()
        }
    }

    private class MutableFakeCredentials(var steamId: String?) : CredentialsProvider {
        override suspend fun currentCredentials(): CredentialsState.Configured? =
            steamId?.let { CredentialsState.Configured(apiKey = "key", steamId = it) }
    }

    private class GatingCredentialsProvider(
        var steamId: String?,
        private val entered: CompletableDeferred<Unit>,
        private val gate: CompletableDeferred<Unit>,
    ) : CredentialsProvider {
        private var first = true

        override suspend fun currentCredentials(): CredentialsState.Configured? {
            if (first) {
                first = false
                entered.complete(Unit)
                gate.await()
            }
            return steamId?.let { CredentialsState.Configured(apiKey = "key", steamId = it) }
        }
    }

    private class FakeCloudCredentialsStore(
        var credentials: CloudCredentials? = null,
    ) : CloudCredentialsStore {
        var writeCount = 0

        override suspend fun readCloudCredentials(): CloudCredentials? = credentials

        override suspend fun writeCloudCredentials(endpoint: String, token: String) {
            writeCount++
            credentials = CloudCredentials(endpoint, token)
        }

        override suspend fun clearCloudCredentials() {
            credentials = null
        }
    }

    private class FakeCloudReadDao : CloudReadDao {
        private var nextId = 1L
        val records = MutableStateFlow<List<CloudReadRecord>>(emptyList())

        override suspend fun insert(record: CloudReadRecord): Long {
            val stored = record.copy(id = nextId++)
            records.value = listOf(stored) + records.value
            return stored.id
        }

        override fun observeRecords(): Flow<List<CloudReadRecord>> = records

        override suspend fun prune(limit: Int) {
            records.value = records.value.take(limit)
        }

        override suspend fun deleteAll() {
            records.value = emptyList()
        }
    }

    private class FixedTimeProvider : TimeProvider {
        override fun nowMillis(): Long = 1_750_000_000_000L
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.of(2026, 9, 15)
    }

    private companion object {
        const val ACCOUNT = "76561198000000001"
        const val OTHER_ACCOUNT = "76561198000000002"

        fun sampleResponse(
            account: String = ACCOUNT,
            nextPosition: String? = null,
            hasMore: Boolean = false,
        ) = CloudPresenceResponseDto(
            account = account,
            transitions = listOf(
                CloudPresenceTransitionDto(
                    v = 2,
                    t = "2026-09-15T00:00:00Z",
                    gameid = "10",
                    gameName = "Portal",
                    personastate = 1,
                ),
            ),
            current = CloudPresenceCurrentDto(
                v = 2,
                lastObservedAt = "2026-09-15T00:10:00Z",
                gameid = "10",
                gameName = "Portal",
                personastate = 1,
            ),
            nextPosition = nextPosition,
            hasMore = hasMore,
            windowStart = "2026-09-15T00:00:00Z",
            windowEnd = "2026-09-15T00:10:00Z",
            readAt = "2026-09-15T00:11:00Z",
        )
    }
}
