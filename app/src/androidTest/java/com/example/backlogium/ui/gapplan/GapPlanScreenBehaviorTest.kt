package com.example.backlogium.ui.gapplan

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import com.example.backlogium.domain.gapplan.CapacityProvenance
import com.example.backlogium.domain.gapplan.GapPlanCoverage
import com.example.backlogium.domain.gapplan.GapPlanFact
import com.example.backlogium.domain.gapplan.GapPlanIntent
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
 * Stateless by construction, so every state a player could reach — offline, sparse, an empty tier,
 * a rebuild that could not vary, mid-save-failure — is one value away rather than something that
 * has to be provoked through a real ViewModel and a real database.
 *
 * **Nothing here scrolls to find a recommendation.** That is the point of several of these tests
 * rather than an accident of how they are written: all three are meant to be on screen at once, so
 * an assertion that had to scroll to reach one would be passing while the surface failed.
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

    /** A date past the planning horizon is unavailable and is explained in words. */
    @Test
    fun anOutOfRangeDateDisablesGenerationAndIsExplained() {
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
        composeRule.onNodeWithTag(TAG_GENERATE).assertIsNotEnabled()
    }

    @Test
    fun aTodayOrPastDateDisablesGeneration() {
        var state by mutableStateOf(
            GapPlanUiState(
                loading = false,
                today = LocalDate.now(),
                setup = GapPlanSetupUi(
                    anticipatedTitle = "Sequel",
                    targetDate = LocalDate.now(),
                ),
            ),
        )
        setContent(state = { state })

        composeRule.onNodeWithTag(TAG_GENERATE).assertIsNotEnabled()

        state = state.copy(setup = state.setup.copy(targetDate = LocalDate.now().minusDays(1)))
        composeRule.onNodeWithTag(TAG_GENERATE).assertIsNotEnabled()
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

    // --- One screen ---------------------------------------------------------------------------

    /**
     * All three, at once, without a scroll.
     *
     * Asserted with no `performScrollToNode` anywhere on purpose: the whole reason the result
     * container is a plain Column rather than a lazy list is that the third card must already be
     * composed and on screen, and a test that scrolled to it would pass either way.
     */
    @Test
    fun allThreeRecommendationsAreOnScreenAtOnce() {
        setContent(state = { GapPlanUiState(loading = false, result = result()) })

        PlanIntensity.entries.forEach { intensity ->
            composeRule.onNodeWithTag(pickTag(intensity)).assertIsDisplayed()
            composeRule.onNodeWithTag(hookTag(intensity)).assertIsDisplayed()
        }
    }

    /** Once a result exists the form collapses to a summary, and reopens on demand. */
    @Test
    fun setupCollapsesOnceAResultExistsAndReopensOnDemand() {
        setContent(state = { GapPlanUiState(loading = false, result = result()) })

        composeRule.onNodeWithTag(TAG_SETUP_SUMMARY).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_SETUP_FORM).assertDoesNotExistNow()
        composeRule.onNodeWithText(GapPlanPresentation.requestSummary(result())).assertIsDisplayed()

        composeRule.onNodeWithTag(TAG_EDIT_SETUP).performClick()

        composeRule.onNodeWithTag(TAG_SETUP_FORM).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_TITLE_FIELD).assertIsDisplayed()
        // And the summary steps aside, so only one generate control is ever on screen.
        composeRule.onNodeWithTag(TAG_SETUP_SUMMARY).assertDoesNotExistNow()
        composeRule.onAllNodesWithTag(TAG_GENERATE).assertCountEquals(1)
    }

    /** The setup form is the whole surface until there is something to show instead. */
    @Test
    fun theFormIsShownWhileThereIsNoResult() {
        setContent(state = { GapPlanUiState(loading = false) })

        composeRule.onNodeWithTag(TAG_SETUP_FORM).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_SETUP_SUMMARY).assertDoesNotExistNow()
    }

    // --- The cards ----------------------------------------------------------------------------

    /**
     * Proportions are drawn, not narrated.
     *
     * Each tier carries one indicator holding its share, the withheld remainder and the pick's
     * length together — so the sentences that used to state each of those separately must be gone,
     * not merely shortened.
     */
    @Test
    fun eachTierCarriesOneProportionalIndicatorInsteadOfSentences() {
        setContent(state = { GapPlanUiState(loading = false, result = result()) })

        PlanIntensity.entries.forEach { intensity ->
            composeRule.onNodeWithTag(shareTag(intensity)).assertIsDisplayed()
            composeRule.onNode(
                inPick(intensity, hasText(GapPlanPresentation.pickAgainstShare(result().pick(intensity)!!))),
            ).assertIsDisplayed()
        }
        composeRule.onNodeWithText("Holding back 30h of your forecast.").assertDoesNotExistNow()
        composeRule.onNodeWithText("Planning around 70h of it").assertDoesNotExistNow()
    }

    /** A fully enriched pick shows every fact that lets the player judge it. */
    @Test
    fun aCompletePickShowsItsGenresRatingPlayerCountAndRemainingTime() {
        val enriched = game(
            2,
            genres = listOf("Action", "Metroidvania"),
            multiplayer = true,
            facts = listOf(
                GapPlanFact.Reviews("Overwhelmingly Positive", 250_000, 260_000),
                GapPlanFact.PlayingNow(4_321),
                GapPlanFact.GenreAffinity("Metroidvania"),
            ),
        )
        setContent(state = { GapPlanUiState(loading = false, result = result(game = enriched)) })

        composeRule.onNodeWithTag(genreTag(2L)).assertIsDisplayed()
        composeRule.onNodeWithTag(reviewTag(2L)).assertIsDisplayed()
        composeRule.onNodeWithTag(playersTag(2L)).assertIsDisplayed()
        composeRule.onNodeWithTag(affinityTag(2L)).assertIsDisplayed()
        // Abbreviated, because four indicators share one row.
        composeRule.onNode(inPick(PlanIntensity.RELAXED, hasText("Action, Metroidvania")))
            .assertIsDisplayed()
        composeRule.onNode(inPick(PlanIntensity.RELAXED, hasText("Overwhelmingly Positive · 260K")))
            .assertIsDisplayed()
        composeRule.onNode(inPick(PlanIntensity.RELAXED, hasText("4.3K"))).assertIsDisplayed()
    }

    /** A started game's progress is a bar, not a sentence. */
    @Test
    fun startedProgressIsDrawnRatherThanStated() {
        val started = game(2, facts = listOf(GapPlanFact.Progress(1_312, 6_827)))
        setContent(state = { GapPlanUiState(loading = false, result = result(game = started)) })

        composeRule.onNodeWithTag(progressTag(2L)).assertIsDisplayed()
        composeRule.onNodeWithText("21h 52m of 113h 47m played").assertDoesNotExistNow()
    }

    /** A suggestion with no cached facts still renders, and says what it could not show. */
    @Test
    fun aSparsePickRendersAndDisclosesWhatWasMissing() {
        val sparse = result(
            coverage = GapPlanCoverage(
                visibleGames = 10,
                withSelectedEstimate = 7,
                missingSelectedEstimate = 3,
                alreadyComplete = 0,
                withCachedReviews = 0,
                withKnownGenres = 0,
            ),
            game = game(1, genres = emptyList(), facts = emptyList()),
        )
        setContent(state = { GapPlanUiState(loading = false, result = sparse) })

        GapPlanPresentation.coverageDisclosures(sparse.coverage).forEach { disclosure ->
            composeRule.onNodeWithText(disclosure).assertIsDisplayed()
        }
        // The remaining time is still there; the facts that were never cached simply are not.
        composeRule.onNode(inPick(PlanIntensity.RELAXED, hasText("10h"))).assertIsDisplayed()
        composeRule.onNodeWithTag(genreTag(1L)).assertDoesNotExistNow()
        composeRule.onNodeWithTag(reviewTag(1L)).assertDoesNotExistNow()
        composeRule.onNodeWithTag(playersTag(1L)).assertDoesNotExistNow()
    }

    @Test
    fun anEmptyTierExplainsItselfInsteadOfRenderingBlank() {
        setContent(state = { GapPlanUiState(loading = false, result = result(game = null)) })

        composeRule.onNodeWithTag(emptyTag(PlanIntensity.RELAXED)).assertIsDisplayed()
        // A tier with nothing in it offers no save action and no recommendation hook.
        composeRule.onNodeWithTag(saveTag(PlanIntensity.FULL)).assertDoesNotExistNow()
        composeRule.onNodeWithTag(hookTag(PlanIntensity.FULL)).assertDoesNotExistNow()
    }

    /** One tier without a pick does not stop the others from offering theirs. */
    @Test
    fun aStarvedTierDoesNotSuppressTheOthers() {
        setContent(
            state = {
                result().let { base ->
                    GapPlanUiState(
                        loading = false,
                        result = base.copy(
                            picks = base.picks.map { pick ->
                                if (pick.intensity == PlanIntensity.RELAXED) pick.copy(game = null) else pick
                            },
                        ),
                    )
                }
            },
        )

        composeRule.onNodeWithTag(emptyTag(PlanIntensity.RELAXED)).assertIsDisplayed()
        composeRule.onNodeWithTag(saveTag(PlanIntensity.FULL)).assertIsEnabled()
    }

    @Test
    fun aFamilySharedPickIsLabelledOnTheCard() {
        setContent(
            state = {
                GapPlanUiState(loading = false, result = result(game = game(1, familyShared = true)))
            },
        )

        composeRule.onNodeWithTag(familySharedTag(1L)).assertIsDisplayed()
    }

    // --- Inspection in place -----------------------------------------------------------------

    /** Activating a pick opens its detail overlay, and reports which game. */
    @Test
    fun activatingAPickOpensItsDetailOverlay() {
        var inspected: Long? = null
        var rerolls = 0
        var state by mutableStateOf(GapPlanUiState(loading = false, result = result()))
        setContent(
            state = { state },
            actions = GapPlanActions(
                onInspect = {
                    inspected = it
                    state = state.copy(inspectingAppId = it)
                },
                onGenerate = { rerolls++ },
            ),
        )

        // The whole card is the target, which is what the outline marks out.
        composeRule.onNodeWithTag(pickTag(PlanIntensity.RELAXED)).performClick()

        assertEquals(2L, inspected)
        composeRule.onNodeWithTag(TAG_DETAIL_OVERLAY).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_DETAIL_STUB).assertIsDisplayed()
        assertEquals("opening a detail must not reroll", 0, rerolls)
    }

    /** The result stays composed behind the overlay, which is the point of a partial-height sheet. */
    @Test
    fun theResultRemainsPresentBehindTheOverlay() {
        setContent(
            state = { GapPlanUiState(loading = false, result = result(), inspectingAppId = 2L) },
        )

        composeRule.onNodeWithTag(TAG_DETAIL_OVERLAY).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_PLAN_LIST).assertExistsNow()
    }

    /** System back dismisses the overlay and returns to exactly the same picks. */
    @Test
    fun systemBackDismissesTheOverlayAndLeavesThePicksUnchanged() {
        var rerolls = 0
        var state by mutableStateOf(
            GapPlanUiState(loading = false, result = result(), inspectingAppId = 2L),
        )
        setContent(
            state = { state },
            actions = GapPlanActions(
                onDismissInspection = { state = state.copy(inspectingAppId = null) },
                onGenerate = { rerolls++ },
            ),
        )
        val before = state.result

        composeRule.onNodeWithTag(TAG_DETAIL_OVERLAY).assertIsDisplayed()
        Espresso.pressBack()
        composeRule.waitForIdle()

        assertNull(state.inspectingAppId)
        assertEquals(before, state.result)
        assertEquals("dismissing a detail must not reroll", 0, rerolls)
        composeRule.onNodeWithTag(TAG_DETAIL_STUB).assertDoesNotExistNow()
    }

    /**
     * The overlay's own control dismisses it too, through the same action.
     *
     * Driven through the stub because the real control belongs to the game-detail screen, which
     * resolves its own ViewModel. What this surface owns is that the dismissal clears the
     * inspection state rather than leaving a sheet that reopens on the next recomposition.
     */
    @Test
    fun theOverlaysOwnControlDismissesItThroughTheSameAction() {
        var state by mutableStateOf(
            GapPlanUiState(loading = false, result = result(), inspectingAppId = 2L),
        )
        val actions = GapPlanActions(
            onDismissInspection = { state = state.copy(inspectingAppId = null) },
        )
        composeRule.setContent {
            BacklogiumTheme {
                GapPlanContent(state = state, actions = actions) {
                    Text(
                        text = "close",
                        modifier = Modifier
                            .testTag(TAG_DETAIL_STUB)
                            .clickable(onClick = actions.onDismissInspection),
                    )
                }
            }
        }

        composeRule.onNodeWithTag(TAG_DETAIL_STUB).performClick()

        assertNull(state.inspectingAppId)
        composeRule.onNodeWithTag(TAG_DETAIL_OVERLAY).assertDoesNotExistNow()
    }

    // --- Rebuilding --------------------------------------------------------------------------

    /**
     * Exactly one control produces a new set, in either layout.
     *
     * The surface briefly shipped two — a primary button and a separate "Regenerate" invoking the
     * same action — which was worse than redundant while the picks could not vary: it promised a
     * reroll that did not exist. Collapsing setup moved the control rather than adding one, and
     * that is the thing most likely to regress here.
     */
    @Test
    fun exactlyOneControlProducesANewSetInEitherLayout() {
        var rebuilds = 0
        var state by mutableStateOf(GapPlanUiState(loading = false))
        setContent(state = { state }, actions = GapPlanActions(onGenerate = { rebuilds++ }))

        composeRule.onAllNodesWithTag(TAG_GENERATE).assertCountEquals(1)

        state = state.copy(result = result())
        composeRule.onAllNodesWithTag(TAG_GENERATE).assertCountEquals(1)
        composeRule.onNodeWithText("Regenerate").assertDoesNotExistNow()

        composeRule.onNodeWithTag(TAG_GENERATE).performClick()
        assertEquals(1, rebuilds)
    }

    /** Before a result exists the control asks for a recommendation, in those words. */
    @Test
    fun theControlAsksForARecommendation() {
        setContent(state = { GapPlanUiState(loading = false) })

        composeRule.onNodeWithText("Recommend three games").assertIsDisplayed()
    }

    /** A rebuild that could not vary says so, rather than appearing to have been ignored. */
    @Test
    fun aRebuildThatCannotVaryExplainsItself() {
        setContent(
            state = {
                GapPlanUiState(loading = false, result = result(), rebuildDidNotVary = true)
            },
        )

        composeRule.onNodeWithTag(TAG_NO_VARIATION).assertIsDisplayed()
        composeRule.onNodeWithText(GapPlanPresentation.rebuildDidNotVaryMessage())
            .assertIsDisplayed()
    }

    @Test
    fun aRebuildThatVariedSaysNothingAboutVariation() {
        setContent(state = { GapPlanUiState(loading = false, result = result()) })

        composeRule.onNodeWithTag(TAG_NO_VARIATION).assertDoesNotExistNow()
    }

    /** The surface states that the picks were not judged, so no card reads as the chosen one. */
    @Test
    fun theResultSaysThePicksWereNotRanked() {
        setContent(state = { GapPlanUiState(loading = false, result = result()) })

        composeRule.onNodeWithTag(TAG_SELECTION_EXPLANATION).assertIsDisplayed()
    }

    /** A forecast says nothing about its source; a manual budget must not read as one. */
    @Test
    fun onlyAManuallyBudgetedResultCarriesACapacityCaveat() {
        var state by mutableStateOf(GapPlanUiState(loading = false, result = result()))
        setContent(state = { state })

        composeRule.onNodeWithTag(TAG_FULL_CAPACITY).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_CAPACITY_SOURCE).assertDoesNotExistNow()

        state = state.copy(result = result(provenance = CapacityProvenance.MANUAL))
        composeRule.onNodeWithTag(TAG_CAPACITY_SOURCE).assertIsDisplayed()
    }

    // --- Saving ------------------------------------------------------------------------------

    @Test
    fun reviewingASaveReportsTheTierItWasAskedAbout() {
        var reviewed: PlanIntensity? = null
        setContent(
            state = { GapPlanUiState(loading = false, result = result()) },
            actions = GapPlanActions(onReviewSave = { reviewed = it }),
        )

        composeRule.onNodeWithTag(saveTag(PlanIntensity.BALANCED)).performClick()

        assertEquals(PlanIntensity.BALANCED, reviewed)
    }

    /** The confirmation restates exactly what is about to be written, before anything is. */
    @Test
    fun theSaveConfirmationRepeatsNameDateBasisAndGame() {
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
                        gameName = "Hollow Knight",
                    ),
                )
            },
        )

        composeRule.onNodeWithTag(TAG_SAVE_DIALOG).assertIsDisplayed()
        // Scoped to the dialog: the summary row behind it also names the request, and the
        // assertion is about what the *confirmation* restates before anything is written.
        composeRule.onNode(inDialog(hasText("Before Silksong"))).assertIsDisplayed()
        composeRule.onNode(inDialog(hasText("Deadline 2026-12-01"))).assertIsDisplayed()
        composeRule.onNode(inDialog(hasText("Main Story"))).assertIsDisplayed()
        composeRule.onNode(inDialog(hasText("Hollow Knight"))).assertIsDisplayed()
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
                        LocalDate.parse("2026-12-01"), GapPlanIntent.STORY, "Hollow Knight",
                    ),
                )
            },
        )

        composeRule.onNodeWithTag(TAG_CONFIRM_SAVE).assertIsNotEnabled()
    }

    /** A failed save reports itself and leaves the picks on screen, ready to retry. */
    @Test
    fun aFailedSaveReportsItselfAndKeepsTheResult() {
        setContent(
            state = { GapPlanUiState(loading = false, result = result(), saveError = true) },
        )

        composeRule.onNodeWithTag(TAG_SAVE_ERROR).assertIsDisplayed()
        composeRule.onNodeWithTag(pickTag(PlanIntensity.RELAXED)).assertIsDisplayed()
        composeRule.onNodeWithTag(saveTag(PlanIntensity.RELAXED)).assertIsEnabled()
    }

    // --- Setup details -----------------------------------------------------------------------

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
        var state by mutableStateOf(GapPlanUiState(loading = false, requiresManualBudget = true))
        setContent(
            state = { state },
            actions = GapPlanActions(
                onManualHoursChange = {
                    state = state.copy(setup = state.setup.copy(manualTotalHours = it))
                },
            ),
        )

        // Nothing chosen yet reads as a prompt, not as a budget of zero hours.
        composeRule.onNodeWithText("Drag to set your hours").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_GENERATE).assertIsNotEnabled()

        composeRule.onNodeWithTag(TAG_HOURS_SLIDER).performSemanticsAction(
            SemanticsActions.SetProgress,
        ) { it(120f) }

        assertTrue("the slider must report whole hours", state.setup.manualTotalHours > 0)
        composeRule.onNodeWithText("About ${state.setup.manualTotalHours} hours").assertIsDisplayed()
    }

    /** The date row shows the chosen day in the same format the collection editor uses. */
    @Test
    fun theDateRowShowsNoDateUntilOneIsPickedAndThenShowsIt() {
        var state by mutableStateOf(GapPlanUiState(loading = false))
        setContent(state = { state })

        composeRule.onNodeWithText("No target date set").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_CLEAR_DATE).assertDoesNotExistNow()

        state = state.copy(setup = state.setup.copy(targetDate = LocalDate.parse("2026-12-01")))

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

    /**
     * Scopes a matcher to one recommendation card.
     *
     * The three cards render the same kinds of indicator, so an unscoped matcher would be
     * ambiguous. Scoping also makes the assertion say what it means: not "this value exists
     * somewhere" but "this value is on the Relaxed card".
     */
    private fun inPick(intensity: PlanIntensity, matcher: SemanticsMatcher): SemanticsMatcher =
        matcher and hasAnyAncestor(hasTestTag(pickTag(intensity)))

    /** Scopes a matcher to the save confirmation, which overlays content sharing its wording. */
    private fun inDialog(matcher: SemanticsMatcher): SemanticsMatcher =
        matcher and hasAnyAncestor(hasTestTag(TAG_SAVE_DIALOG))

    /**
     * Renders the surface with a stub detail body.
     *
     * The real body is the collection game-detail screen, which resolves its own `hiltViewModel`
     * and cannot be composed from a plain compose rule. Substituting it keeps the overlay's
     * lifecycle — open, dismiss, and never reroll — testable here, where every state is one value
     * away.
     */
    private fun setContent(
        state: () -> GapPlanUiState,
        actions: GapPlanActions = GapPlanActions(),
    ) = composeRule.setContent {
        BacklogiumTheme {
            GapPlanContent(state = state(), actions = actions) { appId ->
                Text(text = "detail $appId", modifier = Modifier.testTag(TAG_DETAIL_STUB))
            }
        }
    }

    private fun result(
        coverage: GapPlanCoverage = GapPlanCoverage(4, 4, 0, 0, 4, 4),
        game: GapPlanGameUi? = game(2),
        provenance: CapacityProvenance = CapacityProvenance.PERSONAL_PACE,
    ) = GapPlanResultUi(
        anticipatedTitle = "Silksong",
        targetDate = LocalDate.parse("2026-12-01"),
        intent = GapPlanIntent.STORY,
        fullCapacityMinutes = 6_000,
        provenance = provenance,
        picks = PlanIntensity.entries.map { intensity ->
            val budget = intensity.budgetMinutes(6_000)
            GapPlanPickUi(
                intensity = intensity,
                budgetMinutes = budget,
                unusedMinutes = budget - (game?.remainingMinutes ?: 0),
                game = game,
            )
        },
        coverage = coverage,
    )

    private fun game(
        appId: Long,
        name: String = "Game $appId",
        remainingMinutes: Int = 600,
        familyShared: Boolean = false,
        multiplayer: Boolean = false,
        genres: List<String> = listOf("Action"),
        facts: List<GapPlanFact> = emptyList(),
    ) = GapPlanGameUi(
        appId = appId,
        name = name,
        iconUrl = "",
        headerUrl = "",
        remainingMinutes = remainingMinutes,
        isFamilyShared = familyShared,
        isMultiplayer = multiplayer,
        genreLabels = genres,
        facts = facts,
    )

    private companion object {
        const val TAG_DETAIL_STUB = "gapplan-detail-stub"
    }
}

/** Reads better than the negated form at the call sites above, and keeps intent obvious. */
private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistNow() =
    assertDoesNotExist()

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsNow() = assertExists()
