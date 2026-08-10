package com.example.backlogium.domain

/**
 * Presentation choices shared by the Library and collection overview member lists.
 *
 * The visible fields are deliberately data rather than surface-specific conditionals. Moving
 * from one entry to the next removes fields only; identity and the currently-playing signal stay
 * present at every density.
 */
enum class GameListDensity(
    val label: String,
    val isGrid: Boolean,
    val columns: Int,
    val visibleFields: Set<GameListField>,
) {
    LIST(
        label = "List",
        isGrid = false,
        columns = 1,
        visibleFields = setOf(
            GameListField.IDENTITY,
            GameListField.PLAYTIME,
            GameListField.COMPLETION_PROGRESS,
            GameListField.BADGES,
            GameListField.CURRENTLY_PLAYING,
        ),
    ),
    GRID(
        label = "Grid",
        isGrid = true,
        columns = 2,
        visibleFields = setOf(
            GameListField.IDENTITY,
            GameListField.PLAYTIME,
            GameListField.COMPLETION_PROGRESS,
            GameListField.CURRENTLY_PLAYING,
        ),
    ),
    COMPACT_GRID(
        label = "Compact grid",
        isGrid = true,
        columns = 3,
        visibleFields = setOf(
            GameListField.IDENTITY,
            GameListField.CURRENTLY_PLAYING,
        ),
    ),
    ;

    val showsPlaytime: Boolean get() = GameListField.PLAYTIME in visibleFields
    val showsCompletionProgress: Boolean
        get() = GameListField.COMPLETION_PROGRESS in visibleFields
    val showsBadges: Boolean get() = GameListField.BADGES in visibleFields

    companion object {
        /** Unknown or absent preference values must preserve the current full-detail rendering. */
        fun fromStored(stored: String?): GameListDensity =
            stored?.let { runCatching { valueOf(it) }.getOrNull() } ?: LIST
    }
}

/** Information categories used to prove the density ladder is monotonic. */
enum class GameListField {
    IDENTITY,
    PLAYTIME,
    COMPLETION_PROGRESS,
    BADGES,
    CURRENTLY_PLAYING,
}

/** True when [denser] removes information from [looser] without adding another field. */
fun GameListDensity.isStrictSubsetOf(looser: GameListDensity): Boolean =
    this != looser &&
        looser.visibleFields.containsAll(visibleFields)
