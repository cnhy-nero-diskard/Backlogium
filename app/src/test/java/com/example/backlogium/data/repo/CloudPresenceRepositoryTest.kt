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
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CloudPresenceRepositoryTest {
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

    private fun repository(
        api: CloudPresenceApi,
        store: CloudCredentialsStore,
        records: FakeCloudReadDao,
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        steamId: String?,
    ): CloudPresenceRepository = CloudPresenceRepository(
        api = api,
        credentialsStore = store,
        credentialsProvider = FakeCredentials(steamId),
        settings = settings,
        cloudReadDao = records,
        time = FixedTimeProvider(),
    )

    private class FakeCloudPresenceApi(
        var answer: CloudPresenceResponseDto = sampleResponse(),
    ) : CloudPresenceApi {
        data class Request(val endpoint: String, val authorization: String, val position: String?)

        val requests = mutableListOf<Request>()

        override suspend fun read(
            endpoint: String,
            authorization: String,
            position: String?,
        ): CloudPresenceResponseDto {
            requests += Request(endpoint, authorization, position)
            return answer
        }
    }

    private class FakeCloudCredentialsStore(
        var credentials: CloudCredentials? = null,
    ) : CloudCredentialsStore {
        override suspend fun readCloudCredentials(): CloudCredentials? = credentials

        override suspend fun writeCloudCredentials(endpoint: String, token: String) {
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
