package com.example.backlogium.ui.gapplan

import com.example.backlogium.domain.GameSource
import com.example.backlogium.domain.gapplan.CapacityProvenance
import com.example.backlogium.domain.gapplan.GapPlanCandidate
import com.example.backlogium.domain.gapplan.GapPlanCoverage
import com.example.backlogium.domain.gapplan.GapPlanFact
import com.example.backlogium.domain.gapplan.GapPlanIntent
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
data class GapPlanGameUi(
    val appId: Long,
    val name: String,
    val iconUrl: String,
    val headerUrl: String,
    val remainingMinutes: Int,
    val isFamilyShared: Boolean,
    val isMultiplayer: Boolean,
    /** Store genres, already resolved to labels. Empty means none are cached yet. */
    val genreLabels: List<String>,
    /**
     * The facts that let the player judge this pick, in the order the engine produced them.
     * Deliberately the domain facts rather than pre-rendered strings, so the surface can present
     * them and a test can assert that an absent signal produced no label at all.
     */
    val facts: List<GapPlanFact>,
)

/** One tier's offer as its card renders it. */
data class GapPlanPickUi(
    val intensity: PlanIntensity,
    val budgetMinutes: Int,
    val unusedMinutes: Int,
    /** Null when nothing in the eligible pool fits this tier's share of capacity. */
    val game: GapPlanGameUi?,
) {
    val isEmpty: Boolean get() = game == null

    /** What this tier's pick actually commits, which the capacity bar fills to. */
    val plannedMinutes: Int get() = game?.remainingMinutes ?: 0
}

/** The generated result, held stable until the player rebuilds. */
data class GapPlanResultUi(
    val anticipatedTitle: String,
    val targetDate: LocalDate,
    val intent: GapPlanIntent,
    /**
     * The request's whole forecast, stated once for all three tiers. Without it a Relaxed card
     * showing only its own share would present that reduced figure as all the time the player
     * has — concealing exactly what the intensity choice is for.
     */
    val fullCapacityMinutes: Int,
    val provenance: CapacityProvenance,
    val picks: List<GapPlanPickUi>,
    val coverage: GapPlanCoverage,
) {
    fun pick(intensity: PlanIntensity): GapPlanPickUi? =
        picks.firstOrNull { it.intensity == intensity }
}

/** The confirmation shown before anything is written. */
data class GapPlanSaveConfirmationUi(
    val intensity: PlanIntensity,
    val collectionName: String,
    val targetDate: LocalDate,
    val intent: GapPlanIntent,
    val gameName: String,
)

/**
 * Everything the gap-plan route renders.
 *
 * [result] and [setup] coexist rather than replacing one another: the player can go back to adjust
 * inputs without losing the picks they are looking at, and nothing about setup is persisted.
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
    /**
     * The last rebuild could not produce a different set, because the eligible pool has no other
     * candidate near any tier's share.
     *
     * Held as explicit state rather than inferred by the surface: a control that appears to have
     * been ignored is the exact failure the single rebuild control was introduced to fix, and the
     * only honest answer is to say why nothing changed.
     */
    val rebuildDidNotVary: Boolean = false,
    /** The pick whose detail overlay is open, or null. Inspection never alters the picks. */
    val inspectingAppId: Long? = null,
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

/** A domain candidate to its card, joined with the library artwork the card renders. */
internal fun GapPlanCandidate.toUi(
    iconUrl: String,
    headerUrl: String,
): GapPlanGameUi = GapPlanGameUi(
    appId = appId,
    name = name,
    iconUrl = iconUrl,
    headerUrl = headerUrl,
    remainingMinutes = remainingMinutes,
    isFamilyShared = source == GameSource.FAMILY_SHARED,
    isMultiplayer = multiplayer,
    genreLabels = genreLabels,
    facts = facts,
)
