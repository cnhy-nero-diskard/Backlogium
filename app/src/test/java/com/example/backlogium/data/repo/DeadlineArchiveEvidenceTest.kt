package com.example.backlogium.data.repo

import androidx.room.Room
import com.example.backlogium.data.backup.RoomDatabaseTransactionScope
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.DeadlineEvidenceSchema
import com.example.backlogium.data.local.entity.Collection
import com.example.backlogium.data.local.entity.CollectionMember
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.HltbDatasetLength
import com.example.backlogium.data.local.entity.HltbDatasetMapping
import com.example.backlogium.data.local.entity.HltbDatasetState
import com.example.backlogium.data.local.entity.HltbMatchStatus
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.domain.CollectionMembershipState
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionSort
import com.example.backlogium.domain.CollectionTimeBasis
import com.example.backlogium.domain.DeadlineEvidenceGap
import com.example.backlogium.domain.DeadlineOutcome
import com.example.backlogium.domain.GameSource
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class DeadlineArchiveEvidenceTest {
    private val cutoff = com.example.backlogium.domain.deadlineCutoff("2026-01-01", "UTC")
    private val targetDate = "2026-01-01"
    private val time = MutableDeadlineTime(cutoff - 10_000)
    private lateinit var database: BacklogiumDatabase
    private lateinit var collections: com.example.backlogium.data.local.dao.CollectionDao
    private lateinit var transaction: RoomDatabaseTransactionScope
    private lateinit var archives: DeadlineArchiveRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
        ).addCallback(DeadlineEvidenceSchema).allowMainThreadQueries().build()
        collections = database.collectionDao()
        transaction = RoomDatabaseTransactionScope(database, time)
        archives = DeadlineArchiveRepository(
            database.deadlineArchiveDao(), collections, database.sessionDao(), transaction, time,
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun familyShared_datedClosedSessionsCanProveEstimateMetWithoutSteamReadings() = runBlocking {
        val id = deadline(appId = 10, estimate = 100)
        session(10, cutoff - 3.hours(), cutoff - 2.hours(), minutes = 55)
        session(10, cutoff - 1.hours(), cutoff - 30.minutes(), minutes = 60)
        time.now = cutoff + 1_000

        val result = requireNotNull(archives.preview(id)).result

        assertEquals(DeadlineOutcome.ESTIMATED_MET, result.outcome)
        assertNull(result.members.single().play)
        assertEquals(115L, result.members.single().sessionPlay?.creditedMinutes)
        assertEquals(2, result.members.single().sessionPlay?.sessionCount)
        assertEquals(0L, result.members.single().remainingMinutes)
        assertFalse(DeadlineEvidenceGap.PLAYTIME in result.gaps)
    }

    @Test
    fun familyShared_overlappingSessionsDoNotInflateTheDatedLowerBound() = runBlocking {
        val id = deadline(appId = 11, estimate = 150)
        session(11, cutoff - 3.hours(), cutoff - 1.hours(), minutes = 100)
        session(11, cutoff - 2.hours(), cutoff - 30.minutes(), minutes = 100)
        time.now = cutoff + 1_000

        val result = requireNotNull(archives.preview(id)).result

        assertEquals(DeadlineOutcome.UNDETERMINED, result.outcome)
        assertNull(result.members.single().sessionPlay)
        assertTrue(DeadlineEvidenceGap.PLAYTIME in result.gaps)
    }

    @Test
    fun manualAndOpenOrCutoffSpanningActivityDoNotEstablishDatedPlay() = runBlocking {
        val id = deadline(appId = 12, estimate = 100, manualSharedMinutes = 900)
        database.sessionDao().insert(Session(
            appId = 12, startAt = cutoff - 2.hours(), endAt = null, minutes = 200, open = true,
        ))
        session(12, cutoff - 1.hours(), cutoff + 1, minutes = 200)
        time.now = cutoff + 2_000

        val result = requireNotNull(archives.preview(id)).result

        assertEquals(DeadlineOutcome.UNDETERMINED, result.outcome)
        assertNull(result.members.single().sessionPlay)
        assertTrue(DeadlineEvidenceGap.PLAYTIME in result.gaps)
    }

    @Test
    fun postCutoffSessionAndManualEditDoNotTurnAnInsufficientLowerBoundIntoAShortfall() = runBlocking {
        val id = deadline(appId = 13, estimate = 100)
        session(13, cutoff - 2.hours(), cutoff - 1.hours(), minutes = 40)
        time.now = cutoff + 10
        database.gameDao().upsert(sharedGame(13, manualSharedMinutes = 1_000))
        session(13, cutoff + 100, cutoff + 2.hours(), minutes = 200)

        val result = requireNotNull(archives.preview(id)).result

        assertEquals(DeadlineOutcome.UNDETERMINED, result.outcome)
        assertEquals(40L, result.members.single().sessionPlay?.creditedMinutes)
        assertEquals(1, result.members.single().recordedSessions)
    }

    @Test
    fun collectionSaveCrossingCutoffIsFrozenAsAmbiguousNotAsPartialMembership() = runBlocking {
        val id = deadline(appId = 20, estimate = 100)
        database.gameDao().upsert(sharedGame(21))
        database.hltbDataDao().upsert(estimate(21, 100))
        val baseline = collections.getMembers(id).map {
            CollectionMembershipState(it.appId, it.orderIndex, it.done)
        }
        val advancingDao = collectionDaoAdvancingTimeAtInsert()
        val repository = CollectionRepository(
            collectionDao = advancingDao,
            hiddenGamesRepository = fakeHiddenGamesRepository(),
            time = time,
            transaction = transaction,
        )

        repository.save(CollectionSaveDraft(
            id = id,
            name = "Deadline",
            mode = CollectionMode.DEADLINE_GOAL,
            sort = CollectionSort.COMPLETION_FRACTION,
            targetDate = targetDate,
            accent = null,
            timeBasis = CollectionTimeBasis.MAIN_STORY,
            description = null,
            memberAppIds = listOf(21),
            doneAppIds = emptySet(),
            membershipBaseline = baseline,
        ))
        time.now = cutoff + 2_000

        val preview = requireNotNull(archives.preview(id))
        assertTrue(preview.result.cutoffAmbiguous)
        assertEquals(listOf(20L), preview.result.members.map { it.appId })
        assertEquals(DeadlineOutcome.UNDETERMINED, preview.result.outcome)

        val frozen = archives.confirm(preview)
        assertTrue(frozen.cutoffAmbiguous)
        assertEquals(DeadlineOutcome.UNDETERMINED, frozen.outcome)
        assertEquals(listOf(20L), frozen.members.map { it.appId })
    }

    @Test
    fun basicCollectionConversionCapturesItsExistingDeadlineMembershipAndSource() = runBlocking {
        database.gameDao().upsert(sharedGame(30))
        database.hltbDataDao().upsert(estimate(30, 70))
        val id = collections.insert(Collection(
            name = "Basic", mode = CollectionMode.BASIC, sort = CollectionSort.NAME,
            createdAt = cutoff - 100_000,
        ))
        collections.insertMember(CollectionMember(id, 30, 0))
        assertTrue(database.deadlineArchiveDao().revisions(id).isEmpty())

        collections.updateDetails(
            id, "Deadline", CollectionMode.DEADLINE_GOAL, CollectionSort.COMPLETION_FRACTION,
            targetDate, null, CollectionTimeBasis.MAIN_STORY, null, "UTC",
        )

        val member = database.deadlineArchiveDao().revisions(id).single { it.appId == 30L }
        assertEquals(GameSource.FAMILY_SHARED.name, member.gameSource)
        assertEquals(70, member.mainStory)
    }

    @Test
    fun reenteringDeadlineDoesNotResurrectMemberRemovedWhileBasic() = runBlocking {
        val id = deadlineCollectionWithMember(40)
        database.gameDao().upsert(sharedGame(41))
        database.hltbDataDao().upsert(estimate(41, 80))
        collections.addMember(id, 41)

        updateMode(id, CollectionMode.BASIC)
        val exitId = database.deadlineArchiveDao().revisions(id)
            .last { it.appId == null && it.deadlineActive == false }.id
        collections.removeMember(id, 41)
        updateMode(id, CollectionMode.DEADLINE_GOAL)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE deadline_revisions SET effectiveAt = ? WHERE collectionId = ? AND id <= ?",
            arrayOf(cutoff - 30_000, id, exitId),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE deadline_revisions SET effectiveAt = ? WHERE collectionId = ? AND id > ?",
            arrayOf(cutoff - 10_000, id, exitId),
        )
        time.now = cutoff + 1_000

        val result = requireNotNull(archives.preview(id)).result

        assertTrue(result.membershipKnown)
        assertEquals(listOf(40L), result.members.map { it.appId })
        assertEquals(false, database.deadlineArchiveDao().revisions(id).last { it.appId == 41L }.present)
    }

    @Test
    fun repeatedModeConversionsAndEmptyReentryUseTheLatestMembershipBaseline() = runBlocking {
        val id = deadlineCollectionWithMember(50)
        updateMode(id, CollectionMode.BASIC)
        updateMode(id, CollectionMode.DEADLINE_GOAL)
        updateMode(id, CollectionMode.BASIC)
        collections.removeMember(id, 50)
        updateMode(id, CollectionMode.DEADLINE_GOAL)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE deadline_revisions SET effectiveAt = ? WHERE collectionId = ?",
            arrayOf(cutoff - 1, id),
        )
        time.now = cutoff + 1_000

        val result = requireNotNull(archives.preview(id)).result

        assertTrue(result.membershipKnown)
        assertTrue(result.members.isEmpty())
        assertTrue(DeadlineEvidenceGap.EMPTY_MEMBERSHIP in result.gaps)
    }

    @Test
    fun deadlineExitBeforeCutoffPreventsSameDateReentryAfterCutoffFromMatching() = runBlocking {
        val id = deadlineCollectionWithMember(60)
        updateMode(id, CollectionMode.BASIC)
        val exitId = database.deadlineArchiveDao().revisions(id)
            .last { it.appId == null && it.deadlineActive == false }.id
        database.openHelper.writableDatabase.execSQL(
            "UPDATE deadline_revisions SET effectiveAt = ? WHERE collectionId = ? AND id <= ?",
            arrayOf(cutoff - 1_000, id, exitId),
        )
        updateMode(id, CollectionMode.DEADLINE_GOAL)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE deadline_revisions SET effectiveAt = ? WHERE collectionId = ? AND id > ?",
            arrayOf(cutoff + 1_000, id, exitId),
        )
        time.now = cutoff + 2_000

        val result = requireNotNull(archives.preview(id)).result

        assertNull(result.goalObservedAt)
        assertFalse(result.membershipKnown)
        assertTrue(result.members.isEmpty())
        assertTrue(DeadlineEvidenceGap.MEMBERSHIP_HISTORY in result.gaps)
        assertEquals(DeadlineOutcome.UNDETERMINED, result.outcome)
    }

    @Test
    fun legacyGoalRevisionWithoutModeBoundaryCannotProveCutoffMembership() = runBlocking {
        val id = deadlineCollectionWithMember(62)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE deadline_revisions SET effectiveAt = ?, deadlineActive = NULL WHERE collectionId = ?",
            arrayOf(cutoff - 1, id),
        )
        time.now = cutoff + 1_000

        val result = requireNotNull(archives.preview(id)).result

        assertFalse(result.membershipKnown)
        assertTrue(result.members.isEmpty())
        assertTrue(DeadlineEvidenceGap.MEMBERSHIP_HISTORY in result.gaps)
        assertEquals(DeadlineOutcome.UNDETERMINED, result.outcome)
    }

    @Test
    fun modeChangeBatchCrossingCutoffMarksArchiveAmbiguous() = runBlocking {
        val id = deadlineCollectionWithMember(61)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE deadline_revisions SET effectiveAt = ? WHERE collectionId = ?",
            arrayOf(cutoff - 20_000, id),
        )
        time.now = cutoff - 1
        transaction.runDeadlineEvidence {
            updateMode(id, CollectionMode.BASIC)
            time.now = cutoff + 1
        }
        updateMode(id, CollectionMode.DEADLINE_GOAL)
        time.now = cutoff + 2_000

        val result = requireNotNull(archives.preview(id)).result

        assertTrue(result.cutoffAmbiguous)
        assertEquals(DeadlineOutcome.UNDETERMINED, result.outcome)
        assertTrue(DeadlineEvidenceGap.CUTOFF_AMBIGUITY in result.gaps)
    }

    @Test
    fun datasetBackedEstimateUsesKeyedLookupAndUnchangedPublicationAddsNoRevision() = runBlocking {
        database.gameDao().upsert(sharedGame(31))
        val dataset = database.hltbDatasetDao()
        dataset.upsertMappings(listOf(HltbDatasetMapping(31, 310)))
        dataset.upsertLengths(listOf(HltbDatasetLength(310, 75, 100, 125, 150)))
        dataset.upsert(HltbDatasetState(schemaVersion = 1, datasetVersion = 1, gatheredAt = 1))
        val id = deadlineCollectionWithMember(31)

        val member = database.deadlineArchiveDao().revisions(id).last { it.appId == 31L }
        assertEquals(75, member.mainStory)
        val revisionCount = database.deadlineArchiveDao().revisions(id).size

        dataset.upsert(HltbDatasetState(schemaVersion = 1, datasetVersion = 2, gatheredAt = 2))
        assertEquals(revisionCount, database.deadlineArchiveDao().revisions(id).size)

        dataset.upsertLengths(listOf(HltbDatasetLength(310, 90, 100, 125, 150)))
        dataset.upsert(HltbDatasetState(schemaVersion = 1, datasetVersion = 3, gatheredAt = 3))
        assertEquals(90, database.deadlineArchiveDao().revisions(id).last { it.appId == 31L }.mainStory)
    }

    @Test
    fun gameInsertedAfterDeadlineMembershipCapturesItsSource() = runBlocking {
        val id = deadlineCollectionWithMember(32)

        database.gameDao().upsert(sharedGame(32))

        assertEquals(
            GameSource.FAMILY_SHARED.name,
            database.deadlineArchiveDao().revisions(id).last { it.appId == 32L }.gameSource,
        )
    }

    private suspend fun deadline(
        appId: Long,
        estimate: Int,
        manualSharedMinutes: Int = 0,
    ): Long {
        database.gameDao().upsert(sharedGame(appId, manualSharedMinutes))
        database.hltbDataDao().upsert(estimate(appId, estimate))
        return deadlineCollectionWithMember(appId)
    }

    private suspend fun deadlineCollectionWithMember(appId: Long): Long =
        transaction.runDeadlineEvidence {
            val id = collections.insert(Collection(
                name = "Deadline", mode = CollectionMode.DEADLINE_GOAL,
                sort = CollectionSort.COMPLETION_FRACTION, targetDate = targetDate,
                createdAt = cutoff - 100_000, timeBasis = CollectionTimeBasis.MAIN_STORY,
                deadlineZone = "UTC",
            ))
            collections.insertMember(CollectionMember(id, appId, 0))
            id
        }

    private suspend fun updateMode(id: Long, mode: CollectionMode) {
        val isDeadline = mode == CollectionMode.DEADLINE_GOAL
        collections.updateDetails(
            id,
            if (isDeadline) "Deadline" else "Basic",
            mode,
            if (isDeadline) CollectionSort.COMPLETION_FRACTION else CollectionSort.NAME,
            targetDate.takeIf { isDeadline },
            null,
            CollectionTimeBasis.MAIN_STORY,
            null,
            "UTC".takeIf { isDeadline },
        )
    }

    private suspend fun session(appId: Long, start: Long, end: Long, minutes: Int) {
        database.sessionDao().insert(Session(
            appId = appId, startAt = start, endAt = end, minutes = minutes, open = false,
        ))
    }

    private fun sharedGame(appId: Long, manualSharedMinutes: Int = 0) = Game(
        appId = appId, name = "Shared $appId", iconUrl = "", playtimeForever = 0,
        playtime2Weeks = 0, lastPlaytime = 0, source = GameSource.FAMILY_SHARED,
        manualSharedMinutes = manualSharedMinutes,
    )

    private fun estimate(appId: Long, minutes: Int) = HltbData(
        appId = appId, mainStoryMinutes = minutes, fetchedAt = cutoff - 50,
        matchStatus = HltbMatchStatus.RESOLVED,
    )

    private fun collectionDaoAdvancingTimeAtInsert(): com.example.backlogium.data.local.dao.CollectionDao =
        Proxy.newProxyInstance(
            com.example.backlogium.data.local.dao.CollectionDao::class.java.classLoader,
            arrayOf(com.example.backlogium.data.local.dao.CollectionDao::class.java),
            InvocationHandler { _, method, args ->
                try {
                    val result = method.invoke(collections, *(args ?: emptyArray()))
                    if (method.name == "insertMember") time.now = cutoff + 1
                    result
                } catch (error: InvocationTargetException) {
                    throw error.targetException
                }
            },
        ) as com.example.backlogium.data.local.dao.CollectionDao

    private class MutableDeadlineTime(var now: Long) : TimeProvider {
        override fun nowMillis(): Long = now
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.parse("2026-01-01")
    }

    private fun Int.hours() = this * 3_600_000L
    private fun Int.minutes() = this * 60_000L
}
