package com.example.backlogium.domain.gapplan

import com.example.backlogium.data.repo.CollectionSaveDraft
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.defaultSort
import java.time.format.DateTimeFormatter

/**
 * Turns an accepted pick into the draft the existing atomic collection save already understands.
 *
 * Pure, so the exact end state can be asserted field by field before anything is written. Reusing
 * `CollectionRepository.save` rather than adding a second persistence protocol means the created
 * collection is an *ordinary* deadline collection from the moment it exists: editable, backed up,
 * forecast, and surviving sync like any other. A recommendation-specific table and an auto-updating
 * smart collection were both rejected — each would duplicate collection behaviour, and automatic
 * membership drift would undermine a plan the player committed to follow.
 */
object GapPlanAdoption {

    /** The generated name, so the collection says what it is for without the player naming it. */
    fun collectionName(anticipatedTitle: String): String =
        "$NAME_PREFIX ${anticipatedTitle.trim()}"

    /**
     * Every field is set explicitly, `id` included.
     *
     * `id` is the field that decides what the save *means*: `CollectionRepository.save` branches on
     * `id == 0L` to choose creation over update, so leaving it to a default would eventually
     * overwrite an existing collection instead of creating one. The rest are spelled out for the
     * same reason — a draft is an end state, and an unstated field is a silent decision.
     *
     * Returns null for a tier with no pick. There is nothing to adopt, and a collection with no
     * members is not a plan.
     */
    fun toDraft(snapshot: GapPlanSnapshot, pick: GapPlanPick): CollectionSaveDraft? {
        val game = pick.game ?: return null
        return CollectionSaveDraft(
            // 0 selects creation; any other value would update an existing row.
            id = 0L,
            name = collectionName(snapshot.request.anticipatedTitle),
            mode = CollectionMode.DEADLINE_GOAL,
            // The mode's own default rather than a gap-plan-specific choice, so the collection
            // behaves exactly like one created by hand.
            sort = CollectionMode.DEADLINE_GOAL.defaultSort(),
            targetDate = snapshot.request.targetDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
            // Null accent and description are the neutral defaults a manually created collection
            // gets. Inventing either would make the collection look authored by something else.
            accent = null,
            timeBasis = snapshot.request.intent.timeBasis(),
            description = null,
            memberAppIds = listOf(game.appId),
            // Nothing is complete at creation: the plan is work the player has agreed to do.
            doneAppIds = emptySet(),
        )
    }

    /** Kept as a constant so the name the confirmation shows and the name saved cannot diverge. */
    const val NAME_PREFIX = "Before"
}
