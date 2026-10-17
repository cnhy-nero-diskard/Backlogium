package com.example.backlogium.domain

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.PlaytimeContribution
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.RecoveryAllocationMethods
import com.example.backlogium.data.local.entity.RecoveryProvenance
import com.example.backlogium.data.local.entity.UnknownTimeAllocationRecipe
import com.example.backlogium.data.local.recovery.PlaytimeRecoveryIdentity
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Journaled resumable materialization of a compact recipe (task 5.4): bounded slices, durable
 * cursor, no partial raw credit, and a replay that never counts a materialized share twice — all
 * against the real Room schema.
 */
@RunWith(RobolectricTestRunner::class)
class UnknownTimeMaterializerTest {

    private lateinit var database: BacklogiumDatabase

    private val account = "76561198000000000"
    private val appId = 440L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        runBlocking {
            database.playerProfileDao().upsert(PlayerProfile(steamId = account))
            database.playtimeRecoveryDao().insertContributionChecked(contribution)
            database.playtimeRecoveryDao().commitAllocatedGraph(
                contribution = contribution,
                timed = emptyList(),
                allocations = emptyList(),
                recipe = recipe,
            )
        }
    }

    @After
    fun tearDown() = database.close()

    private val contribution = PlaytimeContribution(
        contributionId = PlaytimeRecoveryIdentity.contributionId(account, appId, 1L, 500L, 605L),
        account = account,
        appId = appId,
        baselineEpoch = 1L,
        fromMinutes = 500L,
        toMinutes = 605L,
        windowEndAt = 2L,
        authorizedMinutes = 105L,
        revision = 1L,
        provenance = RecoveryProvenance.RETROACTIVE_PLAY,
        zone = "UTC",
        createdAt = 1L,
    )

    private val recipe = UnknownTimeAllocationRecipe(
        contributionId = contribution.contributionId,
        account = account,
        appId = appId,
        revision = 1L,
        method = RecoveryAllocationMethods.MULTI_DAY_ESTIMATE,
        quotient = 26L,
        remainderCount = 1L,
        dateStart = "2026-09-01",
        dateEnd = "2026-09-04",
        eligibleCount = 4,
        zone = "UTC",
        updatedAt = 1L,
    )

    private fun materializer() = UnknownTimeMaterializer(
        database = database,
        recoveryDao = database.playtimeRecoveryDao(),
        dailyCredit = RecoveryDailyCreditCoordinator(database.dailyProgressDao()),
    )

    @Test
    fun slicesMaterializeBoundedlyAndAdvanceTheDurableCursor() = runBlocking {
        val dao = database.playtimeRecoveryDao()
        val materializer = materializer()
        assertEquals(105L, dao.recipeResidual(contribution.contributionId))

        // Page 1 — two of four dates, and the cursor remembers where to continue.
        val first = materializer.materialize(contribution.contributionId, now = 1L, pageSize = 2)!!
        assertEquals(listOf("2026-09-01", "2026-09-02"), first.dates)
        assertEquals(53, first.minutes)
        assertEquals(false, first.complete)
        assertEquals(52L, dao.recipeResidual(contribution.contributionId))
        assertEquals("2026-09-02", dao.recipe(contribution.contributionId)!!.materializedThroughDate)

        // Page 2 — done.
        val second = materializer.materialize(contribution.contributionId, now = 1L, pageSize = 2)!!
        assertEquals(listOf("2026-09-03", "2026-09-04"), second.dates)
        assertEquals(true, second.complete)
        assertEquals(0L, dao.recipeResidual(contribution.contributionId))

        // Exactly the recipe total is now materialized, and conservation never moved.
        assertEquals(105L, dao.sumActiveDatedForContribution(contribution.contributionId))
        assertTrue(dao.conservationHolds(contribution.contributionId))
    }

    @Test
    fun malformedStoredRecipeIsReportedAsUnmaterializableWithoutThrowingOrChangingTotals() = runBlocking {
        val dao = database.playtimeRecoveryDao()
        val id = contribution.contributionId
        dao.upsertRecipe(recipe.copy(eligibleCount = recipe.eligibleCount + 1))

        assertNull(materializer().materialize(id, now = 2L, pageSize = 2))
        assertEquals(emptyList<Any>(), dao.activeAllocations(id))
        assertEquals(recipe.copy(eligibleCount = recipe.eligibleCount + 1), dao.recipe(id))
    }

    @Test
    fun anInterruptedRetryNeverCountsAMaterializedShareTwice() = runBlocking {
        val dao = database.playtimeRecoveryDao()
        val materializer = materializer()

        // Simulate a crash after the first slice: the slice and its daily credit are durable, the
        // cursor has advanced, and the process "restarts" with a fresh coordinator.
        materializer.materialize(contribution.contributionId, now = 1L, pageSize = 2)
        val restarted = materializer()

        // A retry continues *after* the cursor instead of re-crediting, and finishes the recipe.
        restarted.materialize(contribution.contributionId, now = 1L, pageSize = 2)
        val beyond = restarted.materialize(contribution.contributionId, now = 1L, pageSize = 2)
        assertEquals("no third page exists past the end", true, beyond!!.complete)
        assertTrue(beyond.isEmpty)

        val rows = dao.activeAllocations(contribution.contributionId)
        assertEquals(4, rows.size)
        assertEquals(105L, rows.sumOf { it.minutes })
        assertTrue(dao.conservationHolds(contribution.contributionId))
    }

    @Test
    fun dailyCreditIsAppliedExactlyOncePerMaterializedDate() = runBlocking {
        val materializer = materializer()
        val dailyDao = database.dailyProgressDao()

        // Four identical "retry" calls: only the first page's dates may ever be credited once.
        repeat(4) {
            materializer.materialize(contribution.contributionId, now = 1L, pageSize = 1)
        }
        repeat(4) {
            materializer.materialize(contribution.contributionId, now = 1L, pageSize = 1)
        }

        assertEquals(27, dailyDao.getByDate("2026-09-01")!!.minutesPlayed)
        assertEquals(26, dailyDao.getByDate("2026-09-02")!!.minutesPlayed)
        assertEquals(26, dailyDao.getByDate("2026-09-03")!!.minutesPlayed)
        assertEquals(26, dailyDao.getByDate("2026-09-04")!!.minutesPlayed)
        assertNull(dailyDao.getByDate("2026-08-31"))
    }

    @Test
    fun unmaterializedCreditIsTheRecipeSlicesAfterTheCursor() = runBlocking {
        val materializer = materializer()

        materializer.materialize(contribution.contributionId, now = 1L, pageSize = 2)

        val remaining = materializer.unmaterializedCredit(
            contributionId = contribution.contributionId,
            from = LocalDate.parse("2026-09-01"),
            to = LocalDate.parse("2026-09-04"),
        )

        // Exactly the two dates after the cursor, with the recipe's exact shares.
        assertEquals(listOf("2026-09-03", "2026-09-04"), remaining.map { it.date.toString() })
        assertEquals(52, remaining.sumOf { it.minutes })
    }

    @Test
    fun fullyExcludedSlicesAdvanceTheCursorWithoutWritingOrCrediting() = runBlocking {
        // Five of the six window dates are fully resolved by evidence; only 2026-09-06 is eligible.
        val sparse = contribution.copy(
            contributionId = PlaytimeRecoveryIdentity.contributionId(account, appId, 1L, 605L, 670L),
            fromMinutes = 605L,
            toMinutes = 670L,
            authorizedMinutes = 65L,
        )
        database.playtimeRecoveryDao().insertContributionChecked(sparse)
        database.playtimeRecoveryDao().commitAllocatedGraph(
            contribution = sparse,
            timed = emptyList(),
            allocations = emptyList(),
            recipe = UnknownTimeAllocationRecipe(
                contributionId = sparse.contributionId,
                account = account,
                appId = appId,
                revision = 1L,
                method = RecoveryAllocationMethods.MULTI_DAY_ESTIMATE,
                quotient = 65L,
                remainderCount = 0L,
                dateStart = "2026-09-01",
                dateEnd = "2026-09-06",
                eligibleCount = 1,
                excludedDatesCsv = "2026-09-01,2026-09-02,2026-09-03,2026-09-04,2026-09-05",
                zone = "UTC",
                updatedAt = 1L,
            ),
        )
        val materializer = materializer()
        val dao = database.playtimeRecoveryDao()
        val dailyDao = database.dailyProgressDao()

        // Page 1 is entirely excluded: zero minutes, no row written, and the cursor still moves.
        val empty = materializer.materialize(sparse.contributionId, now = 1L, pageSize = 1)!!
        assertEquals(0, empty.minutes)
        assertTrue(empty.dates.isEmpty())
        assertNull(dailyDao.getByDate("2026-09-01"))

        // The following page reaches the only eligible date and materializes the whole residual.
        val last = materializer.materialize(sparse.contributionId, now = 1L, pageSize = 5)!!
        assertEquals(listOf("2026-09-06"), last.dates)
        assertEquals(65, last.minutes)
        assertEquals(65, dailyDao.getByDate("2026-09-06")!!.minutesPlayed)
        assertEquals(65L, dao.sumActiveDatedForContribution(sparse.contributionId))
        assertTrue(dao.conservationHolds(sparse.contributionId))
    }
}
