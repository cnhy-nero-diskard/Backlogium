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
            GameListField.ACHIEVEMENT_COUNT,
            GameListField.XP_CONTRIBUTION,
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
            GameListField.ACHIEVEMENT_COUNT,
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
    val showsAchievementCount: Boolean
        get() = GameListField.ACHIEVEMENT_COUNT in visibleFields
    val showsXpContribution: Boolean
        get() = GameListField.XP_CONTRIBUTION in visibleFields

    companion object {
        /** Unknown or absent preference values must preserve the current full-detail rendering. */
        fun fromStored(stored: String?): GameListDensity =
            stored?.let { runCatching { valueOf(it) }.getOrNull() } ?: LIST
    }
}

/**
 * Information categories used to prove the density ladder is monotonic.
 *
 * The achievement count and the XP badge are separate rungs rather than one "badges" field: the
 * count is what a completionist scans for and a grid cell has room for it, while the XP figure is
 * the quietest signal in the app and stays list-only. Splitting them is what lets the count reach
 * [GameListDensity.GRID] without breaking the strict-subset chain.
 */
enum class GameListField {
    IDENTITY,
    PLAYTIME,
    COMPLETION_PROGRESS,
    ACHIEVEMENT_COUNT,
    XP_CONTRIBUTION,
    CURRENTLY_PLAYING,
}

/** True when [denser] removes information from [looser] without adding another field. */
fun GameListDensity.isStrictSubsetOf(looser: GameListDensity): Boolean =
    this != looser &&
        looser.visibleFields.containsAll(visibleFields)
