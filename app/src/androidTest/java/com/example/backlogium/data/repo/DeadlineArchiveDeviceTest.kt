package com.example.backlogium.data.repo

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.backlogium.data.backup.BackupCollection
import com.example.backlogium.data.backup.BackupDeadlineConflict
import com.example.backlogium.data.backup.BackupExportMapper
import com.example.backlogium.data.backup.BackupFile
import com.example.backlogium.data.backup.BackupGame
import com.example.backlogium.data.backup.BackupMergeEngine
import com.example.backlogium.data.backup.BackupValidationResult
import com.example.backlogium.data.backup.BackupValidator
import com.example.backlogium.data.backup.BackupVersionedDecoder
import com.example.backlogium.data.backup.RoomDatabaseTransactionScope
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.DeadlineEvidenceSchema
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.entity.Collection
import com.example.backlogium.data.local.entity.DailyPlaytimeSnapshot
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.HiddenGame
import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.HltbMatchStatus
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionSort
import com.example.backlogium.domain.DeadlineEvidenceGap
import com.example.backlogium.domain.DeadlineOutcome
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.domain.deadlineCutoff
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeadlineArchiveDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun newDatabase() = Room.inMemoryDatabaseBuilder(
        context, BacklogiumDatabase::class.java,
    ).addCallback(DeadlineEvidenceSchema).build()
    private val db = newDatabase()
    private val zone = ZoneId.of("America/New_York")
    private val target = "2026-03-08"
    private val cutoff = deadlineCutoff(target, zone.id)
    private var now = cutoff
    private val time = object : TimeProvider {
        override fun nowMillis() = now
        override fun zone() = zone
        override fun today() = LocalDate.parse(target)
    }
    private val collections = db.collectionDao()
    private val archives = DeadlineArchiveRepository(db.deadlineArchiveDao(), collections, RoomDatabaseTransactionScope(db), time)

    @After
    fun close() = db.close()

    @Test
    fun cutoffMembersSurvivePostCutoffRemovalAndHideAndConcurrentConfirmation() = runBlocking {
        val id = create()
        setupMember(id, 10, amount = 60)
        setupMember(id, 20, amount = 20)
        setupMember(id, 30, amount = 20)
        db.hiddenGameDao().upsertAll(listOf(HiddenGame(30, cutoff - 1)))
        backdate()
        now = cutoff + 2_000
        db.hiddenGameDao().upsertAll(listOf(HiddenGame(20, now)))
        db.hiddenGameDao().delete(listOf(30))
        collections.removeMember(id, 20)
        db.hltbDataDao().upsert(HltbData(20, completionistMinutes = 1, fetchedAt = now, matchStatus = HltbMatchStatus.RESOLVED))

        val preview = requireNotNull(archives.preview(id))
        assertEquals(listOf(10L, 20L, 30L), preview.result.members.map { it.appId })
        assertEquals(listOf(10L, 20L), preview.result.evaluatedMembers.map { it.appId })
        assertEquals(40L, preview.result.evaluatedMembers.last().remainingMinutes)
        assertEquals(DeadlineOutcome.ESTIMATED_NOT_MET, preview.result.outcome)
        val picker = CollectionMembershipRepository(db, object : AccountDataWriteGuard {
            override suspend fun capture(): String = "123"
            override suspend fun check(steamId: String) = Unit
        })
        assertEquals(id, picker.picker(10).first().targets.single().id)
        val first = async { archives.confirm(preview) }
        val other = async { archives.confirm(preview) }
        val frozen = first.await()
        assertEquals(frozen, other.await())
        assertTrue(collections.getAll().isEmpty())
        assertTrue(collections.getAllMembers().isEmpty())
        assertTrue(collections.observeCollections().first().isEmpty())
        assertTrue(picker.picker(10).first().targets.isEmpty())
        db.hiddenGameDao().delete(listOf(20))
        db.gameDao().deleteAll()
        assertEquals(listOf(frozen), archives.archives.first())
        AccountRoomReset(db).resetForAccountChange("456")
        assertTrue(archives.archives.first().isEmpty())
    }

    @Test
    fun deadlineBoundaryUsesSavedZoneAndChangedEvidenceInvalidatesPreview() = runBlocking {
        assertEquals(cutoff, java.time.Instant.parse("2026-03-09T04:00:00Z").toEpochMilli())
        val id = create()
        setupMember(id, 10, amount = 60)
        backdate()
        now = cutoff - 1
        assertNull(archives.preview(id))
        now = cutoff + 2_000
        val preview = requireNotNull(archives.preview(id))
        assertEquals(DeadlineOutcome.ESTIMATED_MET, preview.result.outcome)
        collections.removeMember(id, 10)
        assertTrue(runCatching { archives.confirm(preview) }.exceptionOrNull() is DeadlinePreviewExpired)
        assertNotNull(collections.getById(id))
        assertTrue(archives.archives.first().isEmpty())
    }

    @Test
    fun emptyAndIncompleteMembershipAndUnknownEstimatesAreUndetermined() = runBlocking {
        val legacy = create()
        val empty = create()
        val mixed = create()
        val unknown = create()
        val hidden = create()
        setupMember(mixed, 10, amount = 60)
        setupMember(mixed, 20, amount = 20, estimate = null)
        collections.addMember(unknown, 20)
        collections.addMember(hidden, 20)
        db.hiddenGameDao().upsertAll(listOf(HiddenGame(20, cutoff - 1)))
        backdate()
        // Only the mixed and unknown collections had a visible member with an unknown estimate.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE deadline_revisions SET hidden=0 WHERE appId=20 AND collectionId IN (?,?)",
            arrayOf(mixed, unknown),
        )
        db.openHelper.writableDatabase.execSQL(
            "UPDATE deadline_revisions SET effectiveAt=? WHERE collectionId=?",
            arrayOf(cutoff + 1, legacy),
        )
        now = cutoff + 2_000
        for ((id, gap) in mapOf(
            legacy to DeadlineEvidenceGap.MEMBERSHIP_HISTORY,
            empty to DeadlineEvidenceGap.EMPTY_MEMBERSHIP,
            mixed to DeadlineEvidenceGap.ESTIMATE,
            unknown to DeadlineEvidenceGap.ESTIMATE,
            hidden to DeadlineEvidenceGap.ALL_MEMBERS_HIDDEN,
        )) {
            val result = requireNotNull(archives.preview(id)).result
            assertEquals(DeadlineOutcome.UNDETERMINED, result.outcome)
            assertTrue(result.gaps.contains(gap))
        }
    }

    @Test
    fun exportedArchiveReplacesOnlyItsStaleActiveCopyAndRestoresIdempotently() = runBlocking {
        val id = create()
        setupMember(id, 10, amount = 60)
        setupMember(id, 20, amount = 20)
        backdate()
        now = cutoff + 2_000
        val old = export().copy(formatVersion = 1, deadlineArchives = null, recovery = null)
            .let { file -> file.copy(collections = file.collections.map { it.copy(deadlineZone = null) }) }
        val decodedOld = decode(old)
        db.hiddenGameDao().upsertAll(listOf(HiddenGame(20, now)))
        collections.removeMember(id, 20)
        val frozen = archives.confirm(requireNotNull(archives.preview(id)))
        db.gameDao().deleteAll()
        val exported = decode(export())
        assertEquals(4, exported.formatVersion)
        assertTrue(exported.collections.isEmpty())
        assertTrue(exported.collectionMembers.isEmpty())
        assertEquals(listOf(frozen), exported.deadlineArchives)

        val destination = newDatabase()
        try {
            val destinationCollections = destination.collectionDao()
            destinationCollections.insert(Collection(
                id = id, name = "Independent", mode = CollectionMode.BASIC, sort = CollectionSort.NAME,
                createdAt = cutoff - 1_000_000,
            ))
            val merge = mergeEngine(destination)
            merge.mergeRawWithLockHeld(decodedOld)
            merge.mergeRawWithLockHeld(decodedOld)
            val restoredActive = destinationCollections.getAll().single { it.name == "Deadline" }
            assertNotEquals(id, restoredActive.id)
            assertEquals(2, destinationCollections.getMembers(restoredActive.id).size)
            assertEquals(zone.id, restoredActive.deadlineZone)
            val elapsed = DeadlineArchiveRepository(
                destination.deadlineArchiveDao(), destinationCollections, RoomDatabaseTransactionScope(destination), time,
            )
            assertTrue(requireNotNull(elapsed.preview(restoredActive.id)).result.gaps
                .contains(DeadlineEvidenceGap.MEMBERSHIP_HISTORY))

            merge.mergeRawWithLockHeld(exported)
            merge.mergeRawWithLockHeld(exported)
            merge.mergeRawWithLockHeld(decodedOld)
            assertEquals(listOf("Independent"), destinationCollections.getAll().map { it.name })
            assertTrue(destinationCollections.getAllMembers().isEmpty())
            assertEquals(listOf(frozen), elapsed.archives.first())

            val tampered = exported.copy(deadlineArchives = listOf(frozen.copy(outcome = DeadlineOutcome.ESTIMATED_MET)))
            assertTrue(BackupValidator.validate(tampered) is BackupValidationResult.Invalid)
            assertTrue(runCatching { merge.mergeRawWithLockHeld(tampered) }.isFailure)
            val conflict = exported.copy(
                deadlineArchives = listOf(frozen.copy(name = "Different name")),
                games = listOf(BackupGame(appId = 999, name = "Must roll back", isGoal = false, backfillMinutes = 0)),
            )
            assertTrue(runCatching { merge.mergeRawWithLockHeld(conflict) }.exceptionOrNull() is BackupDeadlineConflict)
            assertNull(destination.gameDao().getById(999))
            assertEquals(listOf(frozen), elapsed.archives.first())
        } finally {
            destination.close()
        }
    }

    @Test
    fun legacyEmptyCollectionRestoredBeforeCutoffHasKnownEmptyMembership() = runBlocking {
        val futureTarget = LocalDate.now(zone).plusDays(1).toString()
        val legacy = decode(export().copy(
            formatVersion = 1,
            recovery = null,
            collections = listOf(BackupCollection(
                100L, "Empty", "DEADLINE_GOAL", "NAME", futureTarget, cutoff - 10_000,
            )),
        ))
        mergeEngine(db).mergeRawWithLockHeld(legacy)
        now = deadlineCutoff(futureTarget, zone.id)
        val result = requireNotNull(archives.preview(100)).result
        assertTrue(result.membershipKnown)
        assertEquals(setOf(DeadlineEvidenceGap.EMPTY_MEMBERSHIP), result.gaps)
        assertEquals(DeadlineOutcome.UNDETERMINED, result.outcome)
    }

    private suspend fun export() = BackupExportMapper(
        gameDao = db.gameDao(), gameAchievementSyncDao = db.gameAchievementSyncDao(),
        achievementDao = db.achievementDao(), sessionDao = db.sessionDao(),
        dailyProgressDao = db.dailyProgressDao(), hltbDataDao = db.hltbDataDao(),
        playerProfileDao = db.playerProfileDao(), collectionDao = collections,
        excludedSharedGameDao = db.excludedSharedGameDao(), hiddenGameDao = db.hiddenGameDao(),
        settings = SettingsDataStore(context),
        credentials = object : CredentialsProvider {
            override suspend fun currentCredentials(): CredentialsState.Configured? = null
        },
        time = time, transaction = RoomDatabaseTransactionScope(db),
        gamePreferenceDao = db.gamePreferenceDao(), deadlineArchiveDao = db.deadlineArchiveDao(),
    ).buildExport()

    private fun decode(file: BackupFile): BackupFile {
        val storage = File.createTempFile("deadline-archive", ".json", context.cacheDir)
        try {
            val json = Json { ignoreUnknownKeys = true }
            storage.writeText(json.encodeToString(file))
            val decoded = requireNotNull(BackupVersionedDecoder.decode(json, storage))
            val validation = BackupValidator.validate(decoded)
            assertTrue(validation.toString(), validation is BackupValidationResult.Valid)
            return decoded
        } finally {
            storage.delete()
        }
    }

    private fun mergeEngine(destination: BacklogiumDatabase) = BackupMergeEngine(
        gameDao = destination.gameDao(), sessionDao = destination.sessionDao(),
        dailyProgressDao = destination.dailyProgressDao(), hltbDataDao = destination.hltbDataDao(),
        achievementDao = destination.achievementDao(), gameAchievementSyncDao = destination.gameAchievementSyncDao(),
        playerProfileDao = destination.playerProfileDao(), collectionDao = destination.collectionDao(),
        excludedSharedGameDao = destination.excludedSharedGameDao(), hiddenGameDao = destination.hiddenGameDao(),
        gamificationUpdater = GamificationUpdater(
            destination.sessionDao(), destination.dailyProgressDao(), destination.playerProfileDao(),
            destination.hltbDataDao(), destination.achievementDao(), destination.gameDao(), destination.hiddenGameDao(),
        ),
        time = time, transaction = RoomDatabaseTransactionScope(destination),
        gamePreferenceDao = destination.gamePreferenceDao(), deadlineArchiveDao = destination.deadlineArchiveDao(),
    )

    private suspend fun create(): Long = collections.insert(Collection(
        name = "Deadline", mode = CollectionMode.DEADLINE_GOAL, sort = CollectionSort.COMPLETION_FRACTION,
        targetDate = target, createdAt = cutoff - 10_000, deadlineZone = zone.id,
    ))

    private suspend fun setupMember(id: Long, appId: Long, amount: Long, estimate: Int? = 60) {
        db.playerProfileDao().upsert(PlayerProfile(steamId = "123"))
        db.gameDao().upsert(Game(appId, "Game $appId", "", 100, 0, 100))
        db.hltbDataDao().upsert(HltbData(appId, completionistMinutes = estimate, fetchedAt = cutoff - 10,
            matchStatus = HltbMatchStatus.RESOLVED))
        collections.addMember(id, appId)
        db.playtimeRecoveryDao().upsertSnapshotRow(DailyPlaytimeSnapshot("123", appId, target,
            zone.id, amount, cutoff - 1_000))
        db.playtimeRecoveryDao().upsertSnapshotRow(DailyPlaytimeSnapshot("123", appId, "2026-03-09",
            zone.id, amount, cutoff + 1_000))
    }

    // The SQLite triggers use the actual write clock; place setup writes before a fixed historical cutoff.
    private fun backdate() {
        db.openHelper.writableDatabase.execSQL("UPDATE deadline_revisions SET effectiveAt=?", arrayOf(cutoff - 1))
    }
}
