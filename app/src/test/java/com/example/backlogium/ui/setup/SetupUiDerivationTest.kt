package com.example.backlogium.ui.setup

import com.example.backlogium.work.setup.SetupOutcome
import com.example.backlogium.work.setup.SetupRunState
import com.example.backlogium.work.setup.SetupStageExecution
import com.example.backlogium.work.setup.SetupStageProgress
import com.example.backlogium.work.setup.fakeStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That every setup surface is a projection of the registry.
 *
 * [aNewlyRegisteredStageAppearsEverywhere] is the point of the whole registry design: a stage this
 * file invents — one no production code knows about — has to turn up in the checklist, in the run
 * order, and in the summary, with none of those surfaces edited to accommodate it. If a future
 * change hardcodes a stage list anywhere, this fails.
 */
class SetupUiDerivationTest {

    private val registered = listOf(
        fakeStage("sync", defaultOptIn = true, execution = SetupStageExecution.IN_SCREEN),
        fakeStage("assets", execution = SetupStageExecution.DETACHED),
        fakeStage("invented_by_this_test", execution = SetupStageExecution.DETACHED),
    )

    @Test
    fun aNewlyRegisteredStageAppearsEverywhere() {
        val run = SetupRunState(
            loaded = true,
            finished = true,
            selected = setOf("sync", "invented_by_this_test"),
            outcomes = mapOf(
                "sync" to SetupOutcome.Succeeded,
                "assets" to SetupOutcome.Skipped,
                "invented_by_this_test" to SetupOutcome.Failed("did not finish"),
            ),
        )

        val rows = setupStagesUi(registered, selection = emptySet(), run = run)

        // Checklist: present, in registered order.
        assertEquals(
            listOf("sync", "assets", "invented_by_this_test"),
            rows.map { it.id },
        )
        // Summary: named, with its reason.
        val summary = setupSummaryLines(rows)
        assertEquals(3, summary.size)
        assertTrue(summary.any { it.contains("Stage invented_by_this_test") && it.contains("did not finish") })
        // Failure is attributed to the stage, never to setup as a whole.
        assertFalse(summary.any { it.equals("Setup failed", ignoreCase = true) })
    }

    @Test
    fun declaredDefaultsDriveTheOnboardingSelection() {
        val defaults = registered.filter { it.defaultOptIn }.map { it.id }.toSet()
        val rows = setupStagesUi(registered, selection = defaults, run = SetupRunState(loaded = true))

        assertEquals(setOf("sync"), rows.filter { it.selected }.map { it.id }.toSet())
    }

    @Test
    fun everyRegisteredStageIsDeselectable() {
        val rows = setupStagesUi(
            registered,
            selection = registered.map { it.id }.toSet(),
            run = SetupRunState(loaded = true),
        )
        // No stage may declare itself mandatory. A stage that cannot be deselected contradicts
        // "Skip setup", which is why credential verification is a precondition and not a stage.
        rows.forEach { row -> assertTrue("${row.id} must be deselectable", row.selectable) }
    }

    @Test
    fun anUnavailableStageIsShownButNotSelectable() {
        val stages = registered + fakeStage("blocked", unavailableReason = "Not in this build")
        val rows = setupStagesUi(stages, selection = emptySet(), run = SetupRunState(loaded = true))
        val blocked = rows.single { it.id == "blocked" }

        assertFalse(blocked.available)
        assertFalse(blocked.selectable)
        assertFalse(blocked.selected)
        // Still listed, with the reason, rather than silently shortening the checklist.
        assertEquals("Not in this build", blocked.unavailableReason)
        assertTrue(setupSummaryLines(rows).any { it.contains("unavailable") })
    }

    @Test
    fun progressIsDeterminateOnlyWhenTheWorkPublishesATotal() {
        val determinate = setupStagesUi(
            registered,
            selection = emptySet(),
            run = SetupRunState(
                loaded = true,
                running = true,
                currentStageId = "sync",
                progress = SetupStageProgress(4, 12, "Portal"),
            ),
        ).single { it.id == "sync" }
        assertEquals(SetupStageProgress(4, 12, "Portal"), determinate.progress)

        val indeterminate = setupStagesUi(
            registered,
            selection = emptySet(),
            run = SetupRunState(
                loaded = true,
                running = true,
                currentStageId = "sync",
                // Total 0: the library sync publishes no per-item progress at all.
                progress = SetupStageProgress(0, 0),
            ),
        ).single { it.id == "sync" }
        // Null rather than a 0 / 0, which would read as a stalled determinate bar.
        assertNull(indeterminate.progress)
        assertTrue(indeterminate.running)
    }

    @Test
    fun finishedStagesStayVisibleWhileLaterOnesRun() {
        val rows = setupStagesUi(
            registered,
            selection = emptySet(),
            run = SetupRunState(
                loaded = true,
                running = true,
                currentStageId = "assets",
                selected = setOf("sync", "assets"),
                outcomes = mapOf("sync" to SetupOutcome.Succeeded),
            ),
        )

        assertEquals(SetupOutcome.Succeeded, rows.single { it.id == "sync" }.outcome)
        assertTrue(rows.single { it.id == "assets" }.running)
    }

    @Test
    fun aRunInFlightReportsItsOwnSelectionRatherThanAPendingOne() {
        // The surface can be recreated mid-run; what is actually happening is authoritative, not a
        // checkbox state that was never started.
        val rows = setupStagesUi(
            registered,
            selection = setOf("assets"),
            run = SetupRunState(loaded = true, running = true, selected = setOf("sync")),
        )

        assertTrue(rows.single { it.id == "sync" }.selected)
        assertFalse(rows.single { it.id == "assets" }.selected)
    }

    @Test
    fun theNotificationRequestIsWarrantedOnlyWhenWorkWillDetach() {
        val inScreenOnly = SetupUiState(
            loading = false,
            stages = setupStagesUi(registered, setOf("sync"), SetupRunState(loaded = true)),
        )
        assertFalse(inScreenOnly.willDetachWork)

        val withDetached = SetupUiState(
            loading = false,
            stages = setupStagesUi(registered, setOf("sync", "assets"), SetupRunState(loaded = true)),
        )
        assertTrue(withDetached.willDetachWork)
    }
}
