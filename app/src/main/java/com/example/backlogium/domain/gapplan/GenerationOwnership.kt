package com.example.backlogium.domain.gapplan

import java.util.concurrent.atomic.AtomicLong

/**
 * Identity for one gap-plan generation.
 *
 * A plain incrementing id rather than the request itself, because two *identical* requests are
 * still two generations: regenerating without changing anything must still invalidate the first
 * one's in-flight lookups. Comparing requests would treat that case as the same generation and let
 * the predecessor publish into its successor.
 */
@JvmInline
value class GenerationId(val value: Long)

/**
 * Decides which results are still allowed to be published.
 *
 * Cancellation alone is not sufficient and that is the whole reason this exists. A coroutine
 * cancelled mid-await may still be resumed at an arbitrary suspension point before it observes the
 * cancellation, and a lookup that finished just before its window expired has a real value in
 * hand. Without an explicit ownership check, either can write into state that now belongs to a
 * different generation — a plan the player is already reading, annotated with another request's
 * counts.
 *
 * Every publish is therefore gated on the id being the current one, not on the coroutine still
 * being alive.
 */
class GenerationOwnership {
    private val nextId = AtomicLong(0)
    private val current = AtomicLong(NONE)

    /**
     * Claims a new identity and invalidates every earlier one. Called when a generation starts,
     * and again on regeneration, so the previous attempt is superseded the moment the new one
     * begins rather than whenever its coroutine happens to notice.
     */
    fun claim(): GenerationId = GenerationId(nextId.incrementAndGet()).also {
        current.set(it.value)
    }

    /**
     * Abandons whatever is current, so nothing in flight may publish. Called when the player
     * navigates away or the surface is closed: the results are no longer wanted, and an
     * unclaimed state is different from a successor having taken over.
     */
    fun abandon() = current.set(NONE)

    /**
     * [NONE] is excluded explicitly rather than left to the fact that [claim] never issues it.
     * "Nothing owns the surface" must not be satisfiable by any id at all — including a
     * default-constructed one — because an accidental match there would publish into a surface the
     * player has already left.
     */
    fun isCurrent(id: GenerationId): Boolean =
        id.value != NONE && current.get() == id.value

    /**
     * Runs [publish] only if [id] still owns the surface, and reports whether it did.
     *
     * The check and the publish are deliberately adjacent: a caller that checked ownership and
     * then published in a later statement could be superseded in between, which is exactly the
     * race this is meant to close.
     */
    inline fun ifCurrent(id: GenerationId, publish: () -> Unit): Boolean {
        if (!isCurrent(id)) return false
        publish()
        return true
    }

    companion object {
        /** No generation owns the surface — before the first, and after abandonment. */
        const val NONE = 0L
    }
}
