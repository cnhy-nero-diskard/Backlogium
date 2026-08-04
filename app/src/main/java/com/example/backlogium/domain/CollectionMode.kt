package com.example.backlogium.domain

/**
 * The intent a custom collection carries, chosen at creation and stored on the collection
 * (add-custom-collections). Four coherent presets rather than a raw config matrix; each mode
 * determines which banner the collection presents.
 *
 * The constant's *name* is the persisted value (Room stores it via a type converter), the same
 * label/identifier trade-off as Focus/Your-games vs `Game.isGoal` — user-facing copy is the
 * UI layer's job.
 */
enum class CollectionMode {
    /** A simple named group of games — the banner presents the member count only. */
    BASIC,

    /** Completion focus — the banner presents aggregate progress + achievements remaining. */
    COMPLETION_GOAL,

    /** Adds a collection-level target date — the banner presents a countdown + progress. */
    DEADLINE_GOAL,

    /** Members are sequenced manually — the banner surfaces the next game to act on. */
    ORDERED_QUEUE,
}
