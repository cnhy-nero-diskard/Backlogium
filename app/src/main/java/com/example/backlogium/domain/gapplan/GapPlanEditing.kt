package com.example.backlogium.domain.gapplan

/**
 * Why a replacement cannot be made. Each is something the surface can state before the player
 * commits to the choice, rather than a failure discovered after it.
 */
enum class GapPlanEditError {
    /** The game being replaced is not in this variant. */
    NOT_A_MEMBER,

    /** The replacement is already in this variant; a plan never contains a game twice. */
    ALREADY_A_MEMBER,

    /** The replacement is not in the retained eligible pool for this generation. */
    NOT_ELIGIBLE,

    /** The swap would push planned minutes past this variant's budget. */
    EXCEEDS_BUDGET,
}

/**
 * Local edits to a generated variant.
 *
 * Every function here is pure and returns a new variant. Nothing regenerates, and nothing touches
 * library or collection state: the player is adjusting a preview, and a preview that reshuffled
 * its other choices whenever one was changed would not be a preview of anything.
 *
 * Validation runs against the snapshot's *retained* eligible pool rather than a fresh derivation,
 * so an edit is judged by the same facts the plan was built from. Re-deriving here would let a
 * background enrichment that landed since generation silently change which swaps are offered.
 */
object GapPlanEditing {

    /**
     * Removes one member. Planned and reserve minutes follow from the membership, so they need no
     * recalculation step that could be forgotten — removing a game updates them by construction.
     */
    fun remove(variant: GapPlanVariant, appId: Long): GapPlanVariant =
        variant.copy(members = variant.members.filterNot { it.appId == appId })

    /**
     * Swaps one member for another eligible candidate, leaving every other choice exactly as it
     * was — including its position, so the plan does not reorder itself under the player.
     */
    fun replace(
        variant: GapPlanVariant,
        eligiblePool: List<GapPlanCandidate>,
        removeAppId: Long,
        addAppId: Long,
    ): Result<GapPlanVariant> {
        val index = variant.members.indexOfFirst { it.appId == removeAppId }
        if (index == -1) return failure(GapPlanEditError.NOT_A_MEMBER)
        if (addAppId != removeAppId && variant.members.any { it.appId == addAppId }) {
            return failure(GapPlanEditError.ALREADY_A_MEMBER)
        }
        val replacement = eligiblePool.firstOrNull { it.appId == addAppId }
            ?: return failure(GapPlanEditError.NOT_ELIGIBLE)

        val planned = variant.plannedMinutes -
            variant.members[index].remainingMinutes +
            replacement.remainingMinutes
        if (planned > variant.budgetMinutes) return failure(GapPlanEditError.EXCEEDS_BUDGET)

        val members = variant.members.toMutableList()
        members[index] = replacement
        return Result.success(variant.copy(members = members))
    }

    /**
     * The candidates that could replace [removeAppId] in this variant right now.
     *
     * Offered rather than merely validated: a replacement the budget cannot hold is filtered out
     * here, so it is never presented as a choice and then refused. Already-present members are
     * excluded for the same reason.
     */
    fun replacementsFor(
        variant: GapPlanVariant,
        eligiblePool: List<GapPlanCandidate>,
        removeAppId: Long,
    ): List<GapPlanCandidate> {
        val outgoing = variant.members.firstOrNull { it.appId == removeAppId } ?: return emptyList()
        val present = variant.members.mapTo(mutableSetOf()) { it.appId }
        val headroom = variant.budgetMinutes - variant.plannedMinutes + outgoing.remainingMinutes
        return eligiblePool
            .filterNot { it.appId in present }
            .filter { it.remainingMinutes <= headroom }
            .sortedWith(GapPlanComposer.PROCESSING_ORDER)
    }

    private fun failure(error: GapPlanEditError): Result<GapPlanVariant> =
        Result.failure(GapPlanEditException(error))
}

/** Carries a [GapPlanEditError] out of a `Result`, so callers branch on the enum, not a string. */
class GapPlanEditException(val error: GapPlanEditError) : IllegalArgumentException(error.name)
