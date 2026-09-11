package com.example.backlogium.ui.gapplan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.domain.gapplan.GapPlanAdoption
import com.example.backlogium.domain.gapplan.GapPlanCandidate
import com.example.backlogium.domain.gapplan.GapPlanCollectionCreator
import com.example.backlogium.domain.gapplan.GapPlanDecoration
import com.example.backlogium.domain.gapplan.GapPlanEditException
import com.example.backlogium.domain.gapplan.GapPlanEditing
import com.example.backlogium.domain.gapplan.GapPlanEngine
import com.example.backlogium.domain.gapplan.GapPlanFeed
import com.example.backlogium.domain.gapplan.GapPlanArtwork
import com.example.backlogium.domain.gapplan.GapPlanIntent
import com.example.backlogium.domain.gapplan.GapPlanLiveCounts
import com.example.backlogium.domain.gapplan.GapPlanRequestException
import com.example.backlogium.domain.gapplan.GapPlanSnapshot
import com.example.backlogium.domain.gapplan.GapPlanVariant
import com.example.backlogium.domain.gapplan.GenerationOwnership
import com.example.backlogium.domain.gapplan.PlanIntensity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * Owns one gap-plan session: the setup buffer, one generated snapshot, local edits to it, and the
 * atomic save.
 *
 * The snapshot is held here rather than re-derived from a flow, and that is deliberate. A result
 * that re-emitted whenever the library, pace, or metadata caches changed would reshuffle a plan
 * under a player who is midway through deciding whether to commit a month to it. Inputs are read
 * once per generation; nothing after that changes the plan except the player.
 */
@HiltViewModel
class GapPlanViewModel @Inject constructor(
    private val feed: GapPlanFeed,
    private val liveCounts: GapPlanLiveCounts,
    private val collectionCreator: GapPlanCollectionCreator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GapPlanUiState())
    val uiState: StateFlow<GapPlanUiState> = _uiState.asStateFlow()

    private val ownership = GenerationOwnership()

    /** The accepted snapshot, with the eligible pool edits are validated against. */
    private var snapshot: GapPlanSnapshot? = null

    /** Icon and header art per app id, joined once so member cards need no second read path. */
    private var artwork: Map<Long, GapPlanArtwork> = emptyMap()

    init {
        viewModelScope.launch {
            // One read, only to learn whether a manual budget is required and to seed artwork.
            // Generation reads the feed again, so a plan is never built from a stale snapshot.
            val seed = feed.snapshots.first()
            _uiState.update {
                it.copy(loading = false, requiresManualBudget = !seed.inputs.paceProfile.isReliable)
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
     * Generates all three variants from local state, then decorates them.
     *
     * The order is the contract: variants are published *before* any network call, so the plan is
     * complete and readable whether or not the decoration ever answers. The generation identity is
     * claimed first, so a regeneration invalidates this attempt the moment it starts rather than
     * whenever this coroutine happens to notice.
     */
    fun generate() {
        val state = _uiState.value
        if (!state.canGenerate) return
        val request = state.setup.toRequest(state.requiresManualBudget) ?: return
        val id = ownership.claim()

        _uiState.update {
            it.copy(generating = true, validationError = null, saveError = false, createdCollectionId = null)
        }
        viewModelScope.launch {
            val fresh = feed.snapshots.first()
            val generated = GapPlanEngine.generate(request, fresh.inputs)
            val plan = generated.getOrElse { error ->
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

            val published = ownership.ifCurrent(id) { publish(plan, fresh.artwork) }
            if (!published) return@launch

            // Only now, with three finalized variants already on screen.
            val counts = liveCounts.fetch(liveCounts.lookupTargets(plan))
            if (counts.isEmpty()) return@launch
            ownership.ifCurrent(id) { publish(GapPlanDecoration.apply(plan, counts), fresh.artwork) }
        }
    }

    /** Removes a member from one variant, leaving the other variants and choices untouched. */
    fun removeMember(intensity: PlanIntensity, appId: Long) = editVariant(intensity) { variant, _ ->
        GapPlanEditing.remove(variant, appId)
    }

    /** Opens the swap sheet with only the candidates this variant's budget can actually hold. */
    fun offerReplacements(intensity: PlanIntensity, appId: Long) {
        val plan = snapshot ?: return
        val variant = plan.variant(intensity) ?: return
        val offered = GapPlanEditing.replacementsFor(variant, plan.eligiblePool, appId)
        _uiState.update {
            it.copy(
                replacement = GapPlanReplacementUi(
                    forAppId = appId,
                    intensity = intensity,
                    candidates = offered.map(::memberUi),
                ),
            )
        }
    }

    fun dismissReplacements() = _uiState.update { it.copy(replacement = null) }

    /**
     * Applies a swap. A rejection leaves the variant exactly as it was — the sheet only ever
     * offers budget-valid candidates, so this branch is a guard rather than a normal path.
     */
    fun replaceMember(intensity: PlanIntensity, removeAppId: Long, addAppId: Long) {
        val plan = snapshot ?: return
        val variant = plan.variant(intensity) ?: return
        GapPlanEditing.replace(variant, plan.eligiblePool, removeAppId, addAppId)
            .onSuccess { updated ->
                snapshot = plan.withVariant(updated)
                _uiState.update { it.copy(result = snapshot?.toUi(), replacement = null) }
            }
            .onFailure { error ->
                if (error is GapPlanEditException) _uiState.update { it.copy(replacement = null) }
            }
    }

    /** Regeneration is always explicit: nothing else replaces a snapshot the player is reading. */
    fun regenerate() = generate()

    /** Opens the confirmation, which restates what is about to be written before it is. */
    fun reviewSave(intensity: PlanIntensity) {
        val plan = snapshot ?: return
        val variant = plan.variant(intensity)?.takeIf { !it.isEmpty } ?: return
        _uiState.update {
            it.copy(
                saveError = false,
                confirmation = GapPlanSaveConfirmationUi(
                    intensity = intensity,
                    collectionName = GapPlanAdoption.collectionName(plan.request.anticipatedTitle),
                    targetDate = plan.request.targetDate,
                    intent = plan.request.intent,
                    memberCount = variant.members.size,
                ),
            )
        }
    }

    fun dismissConfirmation() = _uiState.update { it.copy(confirmation = null) }

    /**
     * Commits the reviewed variant.
     *
     * A failure releases busy state and **keeps the preview**, so the player retries the decision
     * they already made rather than regenerating a plan from scratch.
     */
    fun confirmSave() {
        val plan = snapshot ?: return
        val intensity = _uiState.value.confirmation?.intensity ?: return
        val variant = plan.variant(intensity) ?: return
        _uiState.update { it.copy(saving = true, saveError = false) }
        viewModelScope.launch {
            collectionCreator.create(plan, variant)
                .onSuccess { id ->
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
        ownership.abandon()
        super.onCleared()
    }

    private fun publish(plan: GapPlanSnapshot, art: Map<Long, GapPlanArtwork>) {
        snapshot = plan
        artwork = art
        _uiState.update { it.copy(generating = false, result = plan.toUi(), validationError = null) }
    }

    private fun editVariant(
        intensity: PlanIntensity,
        edit: (GapPlanVariant, GapPlanSnapshot) -> GapPlanVariant,
    ) {
        val plan = snapshot ?: return
        val variant = plan.variant(intensity) ?: return
        snapshot = plan.withVariant(edit(variant, plan))
        _uiState.update { it.copy(result = snapshot?.toUi()) }
    }

    private fun GapPlanSnapshot.withVariant(updated: GapPlanVariant) = copy(
        variants = variants.map { if (it.intensity == updated.intensity) updated else it },
    )

    private fun GapPlanSnapshot.toUi() = GapPlanResultUi(
        anticipatedTitle = request.anticipatedTitle,
        targetDate = request.targetDate,
        intent = request.intent,
        fullCapacityMinutes = capacity.fullCapacityMinutes,
        provenance = capacity.provenance,
        variants = variants.map { variant ->
            GapPlanVariantUi(
                intensity = variant.intensity,
                budgetMinutes = variant.budgetMinutes,
                plannedMinutes = variant.plannedMinutes,
                reserveMinutes = variant.reserveMinutes,
                members = variant.members.map(::memberUi),
            )
        },
        coverage = coverage,
    )

    private fun memberUi(candidate: GapPlanCandidate): GapPlanMemberUi {
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
