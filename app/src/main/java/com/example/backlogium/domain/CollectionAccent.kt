package com.example.backlogium.domain

/**
 * A fixed set of palette tokens a user can assign to a custom collection
 * (refine-collections-ui). The tokens deliberately exclude the milestone-reserved gold and the
 * live-presence-reserved green; an unrecognized stored value falls back to the default neutral
 * styling rather than crashing.
 *
 * The constant's *name* is the persisted value, the same label/identifier trade-off as
 * [CollectionMode] and [CollectionSort].
 */
enum class CollectionAccent {
    STEEL_BLUE,
    VIOLET,
    SAGE,
    SLATE,
    ;

    companion object {
        /** Parse a stored accent name, tolerating a value written by a build that no longer matches. */
        fun parse(name: String?): CollectionAccent? =
            name?.let { runCatching { valueOf(it) }.getOrNull() }
    }
}
