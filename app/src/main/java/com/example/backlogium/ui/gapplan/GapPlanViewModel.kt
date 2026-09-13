package com.example.backlogium.ui.gapplan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.domain.gapplan.GapPlanAdoption
import com.example.backlogium.domain.gapplan.GapPlanArtwork
import com.example.backlogium.domain.gapplan.GapPlanCandidate
import com.example.backlogium.domain.gapplan.GapPlanCollectionCreator
import com.example.backlogium.domain.gapplan.GapPlanDecoration
import com.example.backlogium.domain.gapplan.GapPlanEngine
import com.example.backlogium.domain.gapplan.GapPlanFeed
import com.example.backlogium.domain.gapplan.GapPlanInputs
import com.example.backlogium.domain.gapplan.GapPlanIntent
import com.example.backlogium.domain.gapplan.GapPlanLiveCounts
import com.example.backlogium.domain.gapplan.GapPlanPick
import com.example.backlogium.domain.gapplan.GapPlanRequest
import com.example.backlogium.domain.gapplan.GapPlanRequestException
import com.example.backlogium.domain.gapplan.GapPlanSeeds
import com.example.backlogium.domain.gapplan.GapPlanSnapshot
import com.example.backlogium.domain.gapplan.GenerationOwnership
import com.example.backlogium.domain.gapplan.PlanIntensity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

/**
 * Owns one gap-plan session: the setup buffer, one generated snapshot, and the atomic save.
 *
 * The snapshot is held here rather than re-derived from a flow, and that is deliberate. A result
 * that re-emitted whenever the library, pace, or metadata caches changed would reshuffle picks
 * under a player who is midway through deciding whether to commit a month to one. Inputs are read
 * once per generation; nothing after that changes the picks except the player rebuilding.
 */
@HiltViewModel
class GapPlanViewModel @Inject constructor(
    private val feed: GapPlanFeed,
    private val liveCounts: GapPlanLiveCounts,
    private val collectionCreator: GapPlanCollectionCreator,
    private val seeds: GapPlanSeeds,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GapPlanUiState())
    val uiState: StateFlow<GapPlanUiState> = _uiState.asStateFlow()

    private val ownership = GenerationOwnership()

    /** The result currently on screen. */
    private var snapshot: GapPlanSnapshot? = null

    /** Icon and header art per app id, joined once so pick cards need no second read path. */
    private var artwork: Map<Long, GapPlanArtwork> = emptyMap()

    /** The exact snapshot and pick that the open confirmation reviewed. */
    private data class PendingSave(
        val snapshot: GapPlanSnapshot,
        val pick: GapPlanPick,
    )

    private var pendingSave: PendingSave? = null

    /** Includes post-publication live-count enrichment, which must be cancelled on a reroll. */
    private var generationJob: Job? = null

    init {
        viewModelScope.launch {
            // Keep only the setup gate live. The generated result is a separate snapshot and must
            // not move when the feed re-emits for a date boundary or a pace-confidence change.
            feed.snapshots.collect { current ->
                _uiState.update {
                    it.copy(
                        loading = false,
                        today = current.inputs.today,
                        requiresManualBudget = !current.inputs.paceProfile.isReliable,
                    )
                }
            }
        }
    }

    fun setAnticipatedTitle(title: String) = updateSetup { it.copy(anticipatedTitle = title) }

    fun setTargetDate(date: LocalDate?) = updateSetup { it.copy(targetDate = date) }

    fun setIntent(intent: GapPlanIntent) = updateSetup { it.copy(intent = intent) }

    fun setIncludeUnplayed(include: Boolean) = updateSetup { it.copy(includeUnplayed = include) }

    fun setIncludeStarted(include: Boolean) = updateSetup { it.copy(includeStarted = include) }

    /** Clamped to the slider's own range, so no caller can push the budget outside it. */
    fun setManualTotalHours(hours: Int) = updateSetup {
        it.copy(manualTotalHours = hours.coerceIn(0, MAX_MANUAL_HOURS))
    }

    /**
     * Produces a new set of three picks — the surface's one and only such control.
     *
     * The order is the contract: the picks are published *before* any network call, so the result
     * is complete and readable whether or not the live counts ever answer. The generation identity
     * is claimed first, so a rebuild invalidates the previous attempt the moment it starts rather
     * than whenever its coroutine happens to notice.
     *
     * When a result is already on screen this is a **reroll**, and it is expected to change what is
     * shown. A single new seed can legitimately redraw the same games, so when that happens the
     * selection performs an exact search for another reachable set. If the pool genuinely cannot
     * produce a different set the state says so instead.
     */
    fun generate() {
        val state = _uiState.value
        if (!state.canGenerate) return
        val request = state.setup.toRequest(state.requiresManualBudget) ?: return
        generationJob?.cancel()
        val id = ownership.claim()

        _uiState.update {
            it.copy(
                generating = true,
                validationError = null,
                saveError = false,
                rebuildDidNotVary = false,
                createdCollectionId = null,
            )
        }
        generationJob = viewModelScope.launch {
            val fresh = feed.snapshots.first()
            val previous = snapshot
            // A changed setup is a new request, not a reroll. Only an unchanged request has the
            // explicit SHALL-differ guarantee; edited inputs may legitimately produce the same
            // visible games.
            val reroll = previous != null && previous.request == request
            val drawn = drawDistinctFrom(
                previous = previous.takeIf { reroll },
                request = request,
                inputs = fresh.inputs,
            )
            val plan = drawn.getOrElse { error ->
                ownership.ifCurrent(id) {
                    _uiState.update {
                        it.copy(
                            generating = false,
                            result = null,
                            validationError = (error as? GapPlanRequestException)?.error,
                        )
                    }
                }
                return@launch
            }

            // A rebuild that returned its own previous picks has to say so; a first build has no
            // previous set to differ from and never reports it.
            val didNotVary = reroll && plan.pickedAppIds == previous?.pickedAppIds
            val published = ownership.ifCurrent(id) { publish(plan, fresh.artwork, didNotVary) }
            if (!published) return@launch

            // Only now, with three finalized picks already on screen.
            val counts = liveCounts.fetch(liveCounts.lookupTargets(plan))
            if (counts.isEmpty()) return@launch
            ownership.ifCurrent(id) {
                publish(GapPlanDecoration.apply(plan, counts), fresh.artwork, didNotVary)
            }
        }
    }

    /**
     * Draws until the picks differ from [previous], then proves whether an alternate is reachable.
     *
     * The first draw retains the ordinary seeded selection. If it repeats the visible set, the
     * second draw uses the selection engine's exact alternate search instead of guessing how many
     * seeds are enough to find one.
     */
    private suspend fun drawDistinctFrom(
        previous: GapPlanSnapshot?,
        request: GapPlanRequest,
        inputs: GapPlanInputs,
    ): Result<GapPlanSnapshot> {
        val attempt = GapPlanEngine.generate(request, inputs, seeds.next())
        if (previous == null) return attempt
        val plan = attempt.getOrNull() ?: return attempt
        if (plan.pickedAppIds != previous.pickedAppIds) return attempt
        val fallbackSeed = seeds.next()
        return withContext(Dispatchers.Default) {
            GapPlanEngine.generateDifferentFrom(
                request = request,
                inputs = inputs,
                seed = fallbackSeed,
                previousPickedAppIds = previous.pickedAppIds,
            )
        } ?: attempt
    }

    /**
     * Opens a pick's detail overlay.
     *
     * Inspection is deliberately separate from every other action here: it changes one nullable
     * field and touches neither the snapshot nor the seed, so a player can open all three picks in
     * turn and still accept the one they started with.
     */
    fun inspect(appId: Long) = _uiState.update { it.copy(inspectingAppId = appId) }

    fun dismissInspection() = _uiState.update { it.copy(inspectingAppId = null) }

    /** Opens the confirmation, which restates what is about to be written before it is. */
    fun reviewSave(intensity: PlanIntensity) {
        val plan = snapshot ?: return
        val pick = plan.pick(intensity)?.takeIf { !it.isEmpty } ?: return
        val game = pick.game ?: return
        pendingSave = PendingSave(plan, pick)
        _uiState.update {
            it.copy(
                saveError = false,
                confirmation = GapPlanSaveConfirmationUi(
                    intensity = intensity,
                    collectionName = GapPlanAdoption.collectionName(plan.request.anticipatedTitle),
                    targetDate = plan.request.targetDate,
                    intent = plan.request.intent,
                    gameName = game.name,
                ),
            )
        }
    }

    fun dismissConfirmation() {
        pendingSave = null
        _uiState.update { it.copy(confirmation = null) }
    }

    /**
     * Commits the reviewed pick.
     *
     * A failure releases busy state and **keeps the result**, so the player retries the decision
     * they already made rather than rebuilding a set of picks from scratch — which, now that
     * rebuilding genuinely rerolls, would not even return the same game.
     */
    fun confirmSave() {
        val pending = pendingSave ?: return
        val pick = pending.pick.takeIf { !it.isEmpty } ?: return
        _uiState.update { it.copy(saving = true, saveError = false) }
        viewModelScope.launch {
            collectionCreator.create(pending.snapshot, pick)
                .onSuccess { id ->
                    pendingSave = null
                    _uiState.update {
                        it.copy(saving = false, confirmation = null, createdCollectionId = id)
                    }
                }
                .onFailure {
                    _uiState.update { state -> state.copy(saving = false, saveError = true) }
                }
        }
    }

    fun clearSaveError() = _uiState.update { it.copy(saveError = false) }

    /** Consumed by the route once it has navigated, so a recomposition cannot navigate twice. */
    fun consumeCreatedCollection() = _uiState.update { it.copy(createdCollectionId = null) }

    /**
     * Abandons the surface, so nothing in flight may publish into it. Called when the player
     * leaves: a lookup that finished just before its window expired is still holding a value, and
     * cancellation alone would not stop it.
     */
    override fun onCleared() {
        generationJob?.cancel()
        ownership.abandon()
        super.onCleared()
    }

    private fun publish(
        plan: GapPlanSnapshot,
        art: Map<Long, GapPlanArtwork>,
        didNotVary: Boolean,
    ) {
        snapshot = plan
        artwork = art
        _uiState.update {
            it.copy(
                generating = false,
                result = plan.toUi(),
                validationError = null,
                rebuildDidNotVary = didNotVary,
            )
        }
    }

    private fun GapPlanSnapshot.toUi() = GapPlanResultUi(
        anticipatedTitle = request.anticipatedTitle,
        targetDate = request.targetDate,
        intent = request.intent,
        fullCapacityMinutes = capacity.fullCapacityMinutes,
        provenance = capacity.provenance,
        picks = picks.map { pick ->
            GapPlanPickUi(
                intensity = pick.intensity,
                budgetMinutes = pick.budgetMinutes,
                unusedMinutes = pick.unusedMinutes,
                game = pick.game?.let(::gameUi),
            )
        },
        coverage = coverage,
    )

    private fun gameUi(candidate: GapPlanCandidate): GapPlanGameUi {
        val art = artwork[candidate.appId]
        return candidate.toUi(
            iconUrl = art?.iconUrl.orEmpty(),
            headerUrl = art?.headerUrl.orEmpty(),
        )
    }

    private fun updateSetup(transform: (GapPlanSetupUi) -> GapPlanSetupUi) =
        _uiState.update { it.copy(setup = transform(it.setup), validationError = null) }

    companion object {
        /**
         * The top of the manual-budget slider, in hours.
         *
         * 500 hours is already an implausible amount of play for a gap most players are planning,
         * and the figure only ever scales a plan the player can see — so a ceiling costs nothing
         * and keeps the slider's low end, where real answers live, actually usable.
         */
        const val MAX_MANUAL_HOURS = 500

        /** Slider granularity. Finer steps would imply a precision the estimate does not have. */
        const val MANUAL_HOURS_STEP = 5

    }
}
