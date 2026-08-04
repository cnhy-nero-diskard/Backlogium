package com.example.backlogium.domain

/**
 * How one collection's members are ordered. Persisted as the constant's *name* — renaming one
 * silently resets that collection to its default order — mirroring [LibrarySortKey]'s
 * convention. Each key has one sensible direction rather than a togglable one: descending for
 * the "more is more" keys, ascending for [NAME].
 */
enum class CollectionSort {
    /** Game name, A→Z. */
    NAME,

    /** Completion fraction, highest first. */
    COMPLETION_FRACTION,

    /** Days remaining to the deadline, fewest first. */
    DAYS_REMAINING,

    /** Manual sequence order (ordered-queue members). */
    MANUAL_SEQUENCE,
}

/**
 * The sensible default sort per mode, applied when a collection is created: name for basic,
 * completion fraction for completion goal, days remaining for deadline goal, and manual
 * sequence for ordered queue (spec: "Default sort per mode").
 */
fun CollectionMode.defaultSort(): CollectionSort = when (this) {
    CollectionMode.BASIC -> CollectionSort.NAME
    CollectionMode.COMPLETION_GOAL -> CollectionSort.COMPLETION_FRACTION
    CollectionMode.DEADLINE_GOAL -> CollectionSort.DAYS_REMAINING
    CollectionMode.ORDERED_QUEUE -> CollectionSort.MANUAL_SEQUENCE
}

/** Parse a stored sort-key name, tolerating a value written by a build that no longer matches. */
fun collectionSortOrNull(stored: String?): CollectionSort? =
    stored?.let { runCatching { CollectionSort.valueOf(it) }.getOrNull() }
