package com.example.backlogium.ui.gapplan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.example.backlogium.domain.gapplan.CapacityProvenance
import com.example.backlogium.domain.gapplan.GapPlanCoverage
import com.example.backlogium.domain.gapplan.GapPlanIntent
import com.example.backlogium.domain.gapplan.GapPlanReason
import com.example.backlogium.domain.gapplan.GapPlanRequestError
import com.example.backlogium.domain.gapplan.PlanIntensity
import com.example.backlogium.domain.gapplan.budgetMinutes
import com.example.backlogium.ui.theme.BacklogiumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * The gap-plan surface, driven by hoisted state.
 *
 * Stateless by construction, so every state a player could reach — offline, sparse, empty,
 * mid-save-failure — is one value away rather than something that has to be provoked through a
 * real ViewModel and a real database.
 */
class GapPlanScreenBehaviorTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Nothing is offered until the request could actually be planned. */
    @Test
    fun generation_isUnavailableUntilTheRequestIsComplete() {
        var state by mutableStateOf(GapPlanUiState(loading = false))
        setContent(
            state = { state },
            actions = GapPlanActions(
                onTitleChange = { state = state.copy(setup = state.setup.copy(anticipatedTitle = it)) },
                onTargetDateChange = { state = state.copy(setup = state.setup.copy(targetDate = it)) },
            ),
        )

        composeRule.onNodeWithTag(TAG_GENERATE).assertIsNotEnabled()

        composeRule.onNodeWithTag(TAG_TITLE_FIELD).performTextInput("Silksong")
        composeRule.onNodeWithTag(TAG_GENERATE).assertIsNotEnabled()

        // The date arrives from the picker, which is the only way to set one.
        composeRule.onNodeWithTag(TAG_PICK_DATE).performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithTag(TAG_GENERATE).assertIsNotEnabled()

        state = state.copy(setup = state.setup.copy(targetDate = LocalDate.now().plusDays(60)))
        composeRule.onNodeWithTag(TAG_GENERATE).assertIsEnabled()
    }

    /** A date past the planning horizon is reported, in words that say what to do about it. */
    @Test
    fun aRejectedHorizonIsExplainedRatherThanSilentlyDisablingTheButton() {
        setContent(
            state = {
                GapPlanUiState(
                    loading = false,
                    setup = GapPlanSetupUi(
                        anticipatedTitle = "Sequel",
                        targetDate = LocalDate.now().plusYears(10),
                    ),
                    validationError = GapPlanRequestError.TARGET_DATE_BEYOND_HORIZON,
                )
            },
        )

        composeRule.onNodeWithTag(TAG_VALIDATION).assertIsDisplayed()
        composeRule.onNodeWithText(
            GapPlanPresentation.validationMessage(GapPlanRequestError.TARGET_DATE_BEYOND_HORIZON),
        ).assertIsDisplayed()
    }

    /** The hours field exists only when Personal Pace cannot forecast, and says why. */
    @Test
    fun manualHoursAppearOnlyWhilePaceIsLearning() {
        var state by mutableStateOf(GapPlanUiState(loading = false, requiresManualBudget = false))
        setContent(state = { state })

        composeRule.onNodeWithTag(TAG_HOURS_SLIDER).assertDoesNotExistNow()
        composeRule.onNodeWithText(GapPlanPresentation.reliablePaceExplanation()).assertIsDisplayed()

        state = state.copy(requiresManualBudget = true)
        composeRule.onNodeWithTag(TAG_HOURS_SLIDER).assertIsDisplayed()
        composeRule.onNodeWithText(GapPlanPresentation.manualBudgetExplanation()).assertIsDisplayed()
    }

    /** Both inclusion toggles default on, and switching one narrows only this request. */
    @Test
    fun inclusionTogglesDefaultOnAndReportTheirChanges() {
        var state by mutableStateOf(GapPlanUiState(loading = false))
        setContent(
            state = { state },
            actions = GapPlanActions(
                onIncludeUnplayedChange = {
                    state = state.copy(setup = state.setup.copy(includeUnplayed = it))
                },
            ),
        )

        assertTrue(state.setup.includeUnplayed)
        assertTrue(state.setup.includeStarted)
    }

    /**
     * A Relaxed plan that fills nearly all of its own budget must still show the capacity the
     * intensity withheld — otherwise its reduced budget reads as all the time the player has.
     */
    @Test
    fun aRelaxedPlansWithheldCapacityStaysVisible() {
        setContent(state = { GapPlanUiState(loading = false, result = result()) })

        composeRule.onNodeWithTag(TAG_FULL_CAPACITY).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(GapPlanPresentation.fullCapacityLine(6_000)).assertIsDisplayed()
        composeRule.onNodeWithText("Holding back 30h of your forecast.").performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun allThreeVariantsRenderWithTheirOwnBudgets() {
        setContent(state = { GapPlanUiState(loading = false, result = result()) })

        PlanIntensity.entries.forEach { intensity ->
            composeRule.onNodeWithTag(variantTag(intensity)).performScrollTo().assertIsDisplayed()
        }
        composeRule.onNodeWithTag(TAG_CAPACITY_SOURCE).performScrollTo().assertIsDisplayed()
    }

    /** A plan built without ratings or genres still renders, and says what it could not see. */
    @Test
    fun aSparseResultRendersAndDisclosesWhatWasMissing() {
        val sparse = result(
            coverage = GapPlanCoverage(
                visibleGames = 10,
                withSelectedEstimate = 7,
                missingSelectedEstimate = 3,
                alreadyComplete = 0,
                withCachedReviews = 0,
                withKnownGenres = 0,
            ),
            members = listOf(member(1, reasons = listOf(GapPlanReason.Fit(600)))),
        )
        setContent(state = { GapPlanUiState(loading = false, result = sparse) })

        GapPlanPresentation.coverageDisclosures(sparse.coverage).forEach { disclosure ->
            composeRule.onNodeWithText(disclosure).performScrollTo().assertIsDisplayed()
        }
        // The fit chip is present; no rating or genre chip is invented to fill the space.
        composeRule.onNodeWithText("10h left").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun anEmptyVariantExplainsItselfInsteadOfRenderingBlank() {
        setContent(
            state = {
                GapPlanUiState(loading = false, result = result(members = emptyList()))
            },
        )

        composeRule.onNodeWithText(GapPlanPresentation.emptyVariantMessage())
            .performScrollTo().assertIsDisplayed()
        // A variant with nothing in it offers no save action.
        composeRule.onNodeWithTag(saveTag(PlanIntensity.FULL)).assertDoesNotExistNow()
    }

    @Test
    fun familySharedMembersAreLabelledOnTheCard() {
        setContent(
            state = {
                GapPlanUiState(
                    loading = false,
                    result = result(
                        members = listOf(
                            member(
                                1,
                                familyShared = true,
                                reasons = listOf(GapPlanReason.Fit(600), GapPlanReason.FamilyShared),
                            ),
                        ),
                    ),
                )
            },
        )

        composeRule.onNodeWithText("Family shared").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun removingAMemberReportsTheGameItWasAskedAbout() {
        var removed: Pair<PlanIntensity, Long>? = null
        setContent(
            state = { GapPlanUiState(loading = false, result = result()) },
            actions = GapPlanActions(onRemove = { intensity, appId -> removed = intensity to appId }),
        )

        composeRule.onNodeWithTag(removeTag(2L)).performScrollTo().performClick()

        assertEquals(PlanIntensity.RELAXED to 2L, removed)
    }

    /** Only budget-valid swaps are offered, so none can be chosen and then refused. */
    @Test
    fun theSwapSheetOffersOnlyWhatWasGivenToIt() {
        var chosen: Long? = null
        setContent(
            state = {
                GapPlanUiState(
                    loading = false,
                    result = result(),
                    replacement = GapPlanReplacementUi(
                        forAppId = 2L,
                        intensity = PlanIntensity.RELAXED,
                        candidates = listOf(member(7, name = "Swap Me", remainingMinutes = 120)),
                    ),
                )
            },
            actions = GapPlanActions(onChooseReplacement = { chosen = it }),
        )

        composeRule.onNodeWithTag(replacementOptionTag(7L)).assertIsDisplayed().performClick()

        assertEquals(7L, chosen)
    }

    @Test
    fun anEmptySwapSheetSaysSoRatherThanShowingNothing() {
        setContent(
            state = {
                GapPlanUiState(
                    loading = false,
                    result = result(),
                    replacement = GapPlanReplacementUi(2L, PlanIntensity.RELAXED, emptyList()),
                )
            },
        )

        composeRule.onNodeWithText("Nothing else fits this plan's remaining time.").assertIsDisplayed()
    }

    /**
     * Enrichment that arrives while the player is reading reorders rows and adds chips. The games,
     * and the variants they are in, do not change.
     */
    @Test
    fun lateEnrichmentReordersRowsWithoutChangingMembership() {
        var state by mutableStateOf(
            GapPlanUiState(
                loading = false,
                result = result(
                    members = listOf(
                        member(2, name = "Alpha", multiplayer = true),
                        member(3, name = "Beta", multiplayer = true),
                    ),
                ),
            ),
        )
        setContent(state = { state })

        val before = state.result!!.variant(PlanIntensity.RELAXED)!!.members.map { it.appId }

        // Counts arrive: Beta is busier, so it moves up and both gain a chip.
        state = state.copy(
            result = result(
                members = listOf(
                    member(
                        3, name = "Beta", multiplayer = true,
                        reasons = listOf(GapPlanReason.Fit(600), GapPlanReason.PlayingNow(900)),
                    ),
                    member(
                        2, name = "Alpha", multiplayer = true,
                        reasons = listOf(GapPlanReason.Fit(600), GapPlanReason.PlayingNow(10)),
                    ),
                ),
            ),
        )

        val after = state.result!!.variant(PlanIntensity.RELAXED)!!.members.map { it.appId }
        assertEquals(before.sorted(), after.sorted())
        assertTrue("row order may change", before != after)
        composeRule.onNodeWithText("900 playing now").performScrollTo().assertIsDisplayed()
    }

    /** The confirmation restates exactly what is about to be written, before anything is. */
    @Test
    fun theSaveConfirmationRepeatsNameDateBasisAndCount() {
        setContent(
            state = {
                GapPlanUiState(
                    loading = false,
                    result = result(),
                    confirmation = GapPlanSaveConfirmationUi(
                        intensity = PlanIntensity.RELAXED,
                        collectionName = "Before Silksong",
                        targetDate = LocalDate.parse("2026-12-01"),
                        intent = GapPlanIntent.STORY,
                        memberCount = 2,
                    ),
                )
            },
        )

        composeRule.onNodeWithTag(TAG_SAVE_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithText("Before Silksong").assertIsDisplayed()
        composeRule.onNodeWithText("Deadline 2026-12-01").assertIsDisplayed()
        composeRule.onNodeWithText("Main Story").assertIsDisplayed()
        composeRule.onNodeWithText("2 games").assertIsDisplayed()
    }

    /** While saving, the confirm action is unavailable rather than re-submittable. */
    @Test
    fun theConfirmActionIsDisabledWhileTheSaveIsInFlight() {
        setContent(
            state = {
                GapPlanUiState(
                    loading = false,
                    saving = true,
                    result = result(),
                    confirmation = GapPlanSaveConfirmationUi(
                        PlanIntensity.RELAXED, "Before Silksong",
                        LocalDate.parse("2026-12-01"), GapPlanIntent.STORY, 2,
                    ),
                )
            },
        )

        composeRule.onNodeWithTag(TAG_CONFIRM_SAVE).assertIsNotEnabled()
    }

    /** A failed save reports itself and leaves the plan on screen, ready to retry. */
    @Test
    fun aFailedSaveReportsItselfAndKeepsThePreview() {
        setContent(
            state = { GapPlanUiState(loading = false, result = result(), saveError = true) },
        )

        composeRule.onNodeWithTag(TAG_SAVE_ERROR).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(variantTag(PlanIntensity.RELAXED)).performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(saveTag(PlanIntensity.RELAXED)).performScrollTo().assertIsEnabled()
    }

    /** A cleared date is representable: generation is gated on one being present. */
    @Test
    fun clearingTheDateReportsNoDateRatherThanGuessingOne() {
        var state by mutableStateOf(
            GapPlanUiState(
                loading = false,
                setup = GapPlanSetupUi(
                    anticipatedTitle = "Silksong",
                    targetDate = LocalDate.parse("2026-12-01"),
                ),
            ),
        )
        setContent(
            state = { state },
            actions = GapPlanActions(
                onTargetDateChange = { state = state.copy(setup = state.setup.copy(targetDate = it)) },
            ),
        )

        composeRule.onNodeWithTag(TAG_CLEAR_DATE).performClick()

        assertNull(state.setup.targetDate)
        composeRule.onNodeWithTag(TAG_GENERATE).assertIsNotEnabled()
        // Clear is offered only while there is something to clear.
        composeRule.onNodeWithTag(TAG_CLEAR_DATE).assertDoesNotExistNow()
    }

    /**
     * The slider reports whole hours and states the running total, so the value the plan will be
     * built from is visible while it is being chosen rather than only after.
     */
    @Test
    fun theHoursSliderReportsWholeHoursAndStatesTheRunningTotal() {
        var state by mutableStateOf(
            GapPlanUiState(loading = false, requiresManualBudget = true),
        )
        setContent(
            state = { state },
            actions = GapPlanActions(
                onManualHoursChange = {
                    state = state.copy(setup = state.setup.copy(manualTotalHours = it))
                },
            ),
        )

        // Nothing chosen yet reads as a prompt, not as a budget of zero hours.
        composeRule.onNodeWithText("Drag to set the hours you expect to have").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_GENERATE).assertIsNotEnabled()

        composeRule.onNodeWithTag(TAG_HOURS_SLIDER).performSemanticsAction(
            SemanticsActions.SetProgress,
        ) { it(120f) }

        assertTrue("the slider must report whole hours", state.setup.manualTotalHours > 0)
        composeRule.onNodeWithText("About ${state.setup.manualTotalHours} hours before then")
            .assertIsDisplayed()
    }

    /** The date row shows the chosen day in the same format the collection editor uses. */
    @Test
    fun theDateRowShowsNoDateUntilOneIsPickedAndThenShowsIt() {
        var state by mutableStateOf(GapPlanUiState(loading = false))
        setContent(state = { state })

        composeRule.onNodeWithText("No target date set").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_CLEAR_DATE).assertDoesNotExistNow()

        val picked = LocalDate.parse("2026-12-01")
        state = state.copy(setup = state.setup.copy(targetDate = picked))

        composeRule.onNodeWithTag(TAG_DATE_ROW).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_CLEAR_DATE).assertIsDisplayed()
    }

    /** Dismissing the picker leaves the previous answer alone rather than clearing it. */
    @Test
    fun cancellingThePickerDoesNotChangeTheDate() {
        val picked = LocalDate.parse("2026-12-01")
        var state by mutableStateOf(
            GapPlanUiState(loading = false, setup = GapPlanSetupUi(targetDate = picked)),
        )
        setContent(
            state = { state },
            actions = GapPlanActions(
                onTargetDateChange = { state = state.copy(setup = state.setup.copy(targetDate = it)) },
            ),
        )

        composeRule.onNodeWithTag(TAG_PICK_DATE).performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        assertEquals(picked, state.setup.targetDate)
    }

    private fun setContent(
        state: () -> GapPlanUiState,
        actions: GapPlanActions = GapPlanActions(),
    ) = composeRule.setContent {
        BacklogiumTheme {
            GapPlanContent(state = state(), actions = actions)
        }
    }

    private fun result(
        coverage: GapPlanCoverage = GapPlanCoverage(4, 4, 0, 0, 4, 4),
        members: List<GapPlanMemberUi> = listOf(member(2), member(3)),
    ) = GapPlanResultUi(
        anticipatedTitle = "Silksong",
        targetDate = LocalDate.parse("2026-12-01"),
        intent = GapPlanIntent.STORY,
        fullCapacityMinutes = 6_000,
        provenance = CapacityProvenance.PERSONAL_PACE,
        variants = PlanIntensity.entries.map { intensity ->
            val budget = intensity.budgetMinutes(6_000)
            val planned = members.sumOf { it.remainingMinutes }
            GapPlanVariantUi(
                intensity = intensity,
                budgetMinutes = budget,
                plannedMinutes = planned,
                reserveMinutes = budget - planned,
                members = members,
            )
        },
        coverage = coverage,
    )

    private fun member(
        appId: Long,
        name: String = "Game $appId",
        remainingMinutes: Int = 600,
        familyShared: Boolean = false,
        multiplayer: Boolean = false,
        reasons: List<GapPlanReason> = listOf(GapPlanReason.Fit(remainingMinutes)),
    ) = GapPlanMemberUi(
        appId = appId,
        name = name,
        iconUrl = "",
        headerUrl = "",
        remainingMinutes = remainingMinutes,
        isFamilyShared = familyShared,
        isMultiplayer = multiplayer,
        reasons = reasons,
    )
}

/** Reads better than the negated form at the call sites above, and keeps intent obvious. */
private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistNow() =
    assertDoesNotExist()
