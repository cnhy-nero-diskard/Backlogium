package com.example.backlogium.domain.gapplan

import androidx.room.Room
import com.example.backlogium.data.backup.DatabaseTransactionScope
import com.example.backlogium.data.backup.RoomDatabaseTransactionScope
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.repo.CollectionRepository
import com.example.backlogium.data.repo.HiddenGamesRepository
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionTimeBasis
import com.example.backlogium.domain.GameSource
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.domain.defaultSort
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.ZoneId

/**
 * Turning a preview into a durable collection.
 *
 * The mapping is asserted field by field because a draft is an *end state*: an unstated field is a
 * silent decision, and one of them — `id` — decides whether the save creates a collection or
 * overwrites an existing one.
 */
@RunWith(RobolectricTestRunner::class)
class GapPlanAdoptionTest {

    private lateinit var db: BacklogiumDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun aStoryPlanMapsToACreatingDeadlineDraftWithEveryFieldStated() {
        val snapshot = snapshot(GapPlanIntent.STORY, title = "  Hollow Knight: Silksong  ")
        val variant = snapshot.variant(PlanIntensity.BALANCED)!!

        val draft = GapPlanAdoption.toDraft(snapshot, variant)

        // 0 selects creation. Any other value would update an existing row instead.
        assertEquals(0L, draft.id)
        assertEquals("Before Hollow Knight: Silksong", draft.name)
        assertEquals(CollectionMode.DEADLINE_GOAL, draft.mode)
        assertEquals(CollectionMode.DEADLINE_GOAL.defaultSort(), draft.sort)
        assertEquals("2026-11-10", draft.targetDate)
        assertNull(draft.accent)
        assertNull(draft.description)
        assertEquals(CollectionTimeBasis.MAIN_STORY, draft.timeBasis)
        assertEquals(listOf(1L, 2L, 3L), draft.memberAppIds)
        assertEquals(emptySet<Long>(), draft.doneAppIds)
    }

    @Test fun aCompletionistPlanCarriesTheCompletionistBasis() {
        val snapshot = snapshot(GapPlanIntent.COMPLETIONIST)

        val draft = GapPlanAdoption.toDraft(snapshot, snapshot.variant(PlanIntensity.FULL)!!)

        assertEquals(CollectionTimeBasis.COMPLETIONIST, draft.timeBasis)
    }

    /** The collection contains exactly what the player was looking at when they confirmed. */
    @Test fun anEditedPreviewIsWhatGetsSaved() {
        val snapshot = snapshot(GapPlanIntent.STORY)
        val edited = GapPlanEditing.remove(snapshot.variant(PlanIntensity.FULL)!!, appId = 1)

        assertEquals(listOf(2L, 3L), GapPlanAdoption.toDraft(snapshot, edited).memberAppIds)
    }

    @Test fun familySharedMembersAreSavedLikeAnyOther() {
        val shared = candidate(9, 100).copy(source = GameSource.FAMILY_SHARED)
        val snapshot = snapshot(GapPlanIntent.STORY)
        val variant = snapshot.variant(PlanIntensity.FULL)!!.copy(members = listOf(shared))

        assertEquals(listOf(9L), GapPlanAdoption.toDraft(snapshot, variant).memberAppIds)
    }

    @Test fun duplicateMembershipCannotReachTheDraft() {
        val snapshot = snapshot(GapPlanIntent.STORY)
        val duplicated = snapshot.variant(PlanIntensity.FULL)!!.let {
            it.copy(members = it.members + it.members.first())
        }

        assertEquals(listOf(1L, 2L, 3L), GapPlanAdoption.toDraft(snapshot, duplicated).memberAppIds)
    }

    /** The collection row and every membership row commit as one unit, through the existing path. */
    @Test fun savingCreatesAnOrdinaryDeadlineCollectionWithItsMembership() = runTest {
        db.gameDao().upsertAll((1L..3L).map(::game))
        val creator = creator()
        val snapshot = snapshot(GapPlanIntent.STORY)

        val id = creator.create(snapshot, snapshot.variant(PlanIntensity.FULL)!!).getOrThrow()

        val collection = db.collectionDao().getById(id)!!
        assertEquals("Before Anticipated Game", collection.name)
        assertEquals(CollectionMode.DEADLINE_GOAL, collection.mode)
        assertEquals("2026-11-10", collection.targetDate)
        assertEquals(CollectionTimeBasis.MAIN_STORY, collection.timeBasis)
        assertEquals(
            listOf(1L, 2L, 3L),
            db.collectionDao().getMembers(id).sortedBy { it.orderIndex }.map { it.appId },
        )
        assertTrue(db.collectionDao().getMembers(id).none { it.done })
    }

    /**
     * A failure must leave the preview intact and retryable. Being sent back to regenerate a plan
     * the player had already decided on is the difference between a recoverable error and a lost
     * decision.
     */
    @Test fun aFailedSaveReportsTheFailureAndLeavesTheSnapshotUsable() = runTest {
        db.gameDao().upsertAll((1L..3L).map(::game))
        val snapshot = snapshot(GapPlanIntent.STORY)
        val transaction = FlakyTransaction()

        val result = creator(transaction).create(snapshot, snapshot.variant(PlanIntensity.FULL)!!)

        assertTrue(result.isFailure)
        // The failure is captured, never thrown into the surface: a lost plan and a retryable one
        // are the difference between this and letting it propagate.
        assertNull(db.collectionDao().observeCollections().first().firstOrNull())
        // The snapshot the caller still holds is unchanged and can be submitted again.
        assertEquals(
            listOf(1L, 2L, 3L),
            snapshot.variant(PlanIntensity.FULL)!!.members.map { it.appId },
        )

        // Retrying the very same accepted membership succeeds.
        transaction.failNext = false
        val id = creator(transaction).create(snapshot, snapshot.variant(PlanIntensity.FULL)!!)
            .getOrThrow()
        assertEquals(
            listOf(1L, 2L, 3L),
            db.collectionDao().getMembers(id).sortedBy { it.orderIndex }.map { it.appId },
        )
    }

    /**
     * Fails the commit boundary itself, which is the only way to reach the interrupted-write case:
     * `collection_members` deliberately has no foreign key to `games`, because a transient Steam
     * omission must not cascade-delete a player's membership rows.
     */
    private class FlakyTransaction(var failNext: Boolean = true) : DatabaseTransactionScope {
        override suspend fun <R> run(block: suspend () -> R): R {
            if (failNext) throw IllegalStateException("commit refused")
            return block()
        }
    }

    /**
     * A gap-plan collection is an ordinary one once created: later changes to recommendation
     * inputs cannot reach back and alter what the player agreed to.
     */
    @Test fun membershipIsStableAfterRecommendationInputsChange() = runTest {
        db.gameDao().upsertAll((1L..4L).map(::game))
        val creator = creator()
        val snapshot = snapshot(GapPlanIntent.STORY)
        val id = creator.create(snapshot, snapshot.variant(PlanIntensity.FULL)!!).getOrThrow()

        // A later, quite different plan is generated and saved separately.
        val later = snapshot(GapPlanIntent.COMPLETIONIST, title = "Something Else").let {
            it.copy(
                variants = it.variants.map { variant ->
                    variant.copy(members = listOf(candidate(4, 100)))
                },
            )
        }
        creator.create(later, later.variant(PlanIntensity.FULL)!!).getOrThrow()

        assertEquals(
            listOf(1L, 2L, 3L),
            db.collectionDao().getMembers(id).sortedBy { it.orderIndex }.map { it.appId },
        )
        assertEquals(2, db.collectionDao().observeCollections().first().size)
    }

    private fun creator(
        transaction: DatabaseTransactionScope = RoomDatabaseTransactionScope(db),
    ) = GapPlanCollectionCreator(
        CollectionRepository(
            collectionDao = db.collectionDao(),
            hiddenGamesRepository = HiddenGamesRepository(
                hiddenGameDao = db.hiddenGameDao(),
                gameDao = db.gameDao(),
                storeCacheDao = db.gameGenreCacheDao(),
                time = FixedTime,
            ),
            time = FixedTime,
            transaction = transaction,
        ),
    )

    private fun snapshot(
        intent: GapPlanIntent,
        title: String = "Anticipated Game",
    ): GapPlanSnapshot {
        val members = listOf(candidate(1, 100), candidate(2, 200), candidate(3, 300))
        return GapPlanSnapshot(
            request = gapRequest(
                title = title,
                targetDate = LocalDate.parse("2026-11-10"),
                intent = intent,
            ),
            capacity = GapPlanCapacity(
                fullCapacityMinutes = 1_000,
                provenance = CapacityProvenance.PERSONAL_PACE,
                startDate = TODAY.plusDays(1),
                endDate = LocalDate.parse("2026-11-10"),
            ),
            variants = PlanIntensity.entries.map {
                GapPlanVariant(it, budgetMinutes = 1_000, members = members)
            },
            coverage = GapPlanCoverage(3, 3, 0, 0, 0, 0),
            eligiblePool = members,
        )
    }

    private fun game(appId: Long) = Game(
        appId = appId, name = "Game $appId", iconUrl = "", playtimeForever = 0,
        playtime2Weeks = 0, lastPlaytime = 0,
    )

    private object FixedTime : TimeProvider {
        override fun nowMillis(): Long = 1_700_000_000_000L
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = TODAY
    }
}
