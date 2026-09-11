package com.example.backlogium.ui.gapplan

import com.example.backlogium.domain.GameSource
import com.example.backlogium.domain.gapplan.CapacityProvenance
import com.example.backlogium.domain.gapplan.GapPlanCandidate
import com.example.backlogium.domain.gapplan.GapPlanCoverage
import com.example.backlogium.domain.gapplan.GapPlanIntent
import com.example.backlogium.domain.gapplan.GapPlanReason
import com.example.backlogium.domain.gapplan.GapPlanRequest
import com.example.backlogium.domain.gapplan.GapPlanRequestError
import com.example.backlogium.domain.gapplan.PlanIntensity
import java.time.LocalDate

/** The setup form's buffered values, kept separate from the generated result. */
data class GapPlanSetupUi(
    val anticipatedTitle: String = "",
    val targetDate: LocalDate? = null,
    val intent: GapPlanIntent = GapPlanIntent.STORY,
    val includeUnplayed: Boolean = true,
    val includeStarted: Boolean = true,
    /**
     * Only collected, and only required, when Personal Pace is learning.
     *
     * An `Int` rather than free text because this is an estimate, not a measurement: the setup
     * copy asks for *roughly* how many hours the player expects, and a text field would invite a
     * precision the answer does not have. Zero means "not chosen yet", which is what gates
     * generation.
     */
    val manualTotalHours: Int = 0,
) {
    val manualHoursValue: Int? get() = manualTotalHours.takeIf { it > 0 }
}

/** One suggested game as its card renders it. */
data class GapPlanMemberUi(
    val appId: Long,
    val name: String,
    val iconUrl: String,
    val headerUrl: String,
    val remainingMinutes: Int,
    val isFamilyShared: Boolean,
    val isMultiplayer: Boolean,
    /**
     * Fact-bearing chips, in the order the engine produced them. Deliberately the domain reasons
     * rather than pre-rendered strings, so the surface can present them and a test can assert that
     * an absent signal produced no chip at all.
     */
    val reasons: List<GapPlanReason>,
)

/** One plan variant as its card renders it. */
data class GapPlanVariantUi(
    val intensity: PlanIntensity,
    val budgetMinutes: Int,
    val plannedMinutes: Int,
    val reserveMinutes: Int,
    val members: List<GapPlanMemberUi>,
) {
    val isEmpty: Boolean get() = members.isEmpty()
}

/** The generated result, held stable until the player regenerates or edits it. */
data class GapPlanResultUi(
    val anticipatedTitle: String,
    val targetDate: LocalDate,
    val intent: GapPlanIntent,
    /**
     * The request's whole forecast, stated once for all three variants. Without it a Relaxed card
     * showing only its own budget and reserve would present its reduced budget as all the time the
     * player has — concealing exactly what the intensity choice is for.
     */
    val fullCapacityMinutes: Int,
    val provenance: CapacityProvenance,
    val variants: List<GapPlanVariantUi>,
    val coverage: GapPlanCoverage,
) {
    fun variant(intensity: PlanIntensity): GapPlanVariantUi? =
        variants.firstOrNull { it.intensity == intensity }
}

/** The confirmation shown before anything is written. */
data class GapPlanSaveConfirmationUi(
    val intensity: PlanIntensity,
    val collectionName: String,
    val targetDate: LocalDate,
    val intent: GapPlanIntent,
    val memberCount: Int,
)

/** An offered swap for one member, already filtered to what the budget can hold. */
data class GapPlanReplacementUi(
    val forAppId: Long,
    val intensity: PlanIntensity,
    val candidates: List<GapPlanMemberUi>,
)

/**
 * Everything the gap-plan route renders.
 *
 * [result] and [setup] coexist rather than replacing one another: the player can go back to adjust
 * inputs without losing the plan they are looking at, and nothing about setup is persisted.
 */
data class GapPlanUiState(
    val loading: Boolean = true,
    val setup: GapPlanSetupUi = GapPlanSetupUi(),
    /** Personal Pace is learning, so a one-off total-hours budget is required. */
    val requiresManualBudget: Boolean = false,
    val generating: Boolean = false,
    val saving: Boolean = false,
    val result: GapPlanResultUi? = null,
    val validationError: GapPlanRequestError? = null,
    val saveError: Boolean = false,
    val confirmation: GapPlanSaveConfirmationUi? = null,
    val replacement: GapPlanReplacementUi? = null,
    /** Set once a save succeeds, so the route can open the created collection. */
    val createdCollectionId: Long? = null,
) {
    /** Generation is offered only when the request would actually validate. */
    val canGenerate: Boolean
        get() = !generating &&
            setup.anticipatedTitle.isNotBlank() &&
            setup.targetDate != null &&
            (!requiresManualBudget || (setup.manualHoursValue ?: 0) > 0)
}

/** The setup buffer as the domain request, or null while it is still incomplete. */
internal fun GapPlanSetupUi.toRequest(requiresManualBudget: Boolean): GapPlanRequest? {
    val date = targetDate ?: return null
    return GapPlanRequest(
        anticipatedTitle = anticipatedTitle.trim(),
        targetDate = date,
        intent = intent,
        includeUnplayed = includeUnplayed,
        includeStarted = includeStarted,
        manualTotalHours = manualHoursValue.takeIf { requiresManualBudget },
    )
}

/** A domain candidate to its card, joined with the library facts the card renders. */
internal fun GapPlanCandidate.toUi(
    iconUrl: String,
    headerUrl: String,
): GapPlanMemberUi = GapPlanMemberUi(
    appId = appId,
    name = name,
    iconUrl = iconUrl,
    headerUrl = headerUrl,
    remainingMinutes = remainingMinutes,
    isFamilyShared = source == GameSource.FAMILY_SHARED,
    isMultiplayer = multiplayer,
    reasons = reasons,
)
