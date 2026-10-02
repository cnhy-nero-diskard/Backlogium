package com.example.backlogium.ui.gamedetail

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow

enum class AchievementFilter { ALL, UNLOCKED, LOCKED }

data class AchievementLens(
    val sort: AchievementSort = AchievementSort.DATE_ACHIEVED,
    val filter: AchievementFilter = AchievementFilter.ALL,
)

/** Saved with the presentation's visit token, rather than a persistent game preference. */
internal class DetailVisitState(private val saved: SavedStateHandle) {
    val lens = MutableStateFlow(AchievementLens(
        sort = AchievementSort.entries.firstOrNull { it.name == saved.get<String>("achievementSort") }
            ?: AchievementSort.DATE_ACHIEVED,
        filter = AchievementFilter.entries.firstOrNull { it.name == saved.get<String>("achievementFilter") }
            ?: AchievementFilter.ALL,
    ))

    fun open(token: String) {
        if (saved.get<String>("detailVisit") == token) return
        saved["detailVisit"] = token
        update(AchievementLens())
    }

    fun setSort(value: AchievementSort) = update(lens.value.copy(sort = value))
    fun setFilter(value: AchievementFilter) = update(lens.value.copy(filter = value))

    private fun update(value: AchievementLens) {
        saved["achievementSort"] = value.sort.name
        saved["achievementFilter"] = value.filter.name
        lens.value = value
    }
}

internal fun List<AchievementUi>.visibleThrough(lens: AchievementLens): List<AchievementUi> =
    filter { row -> when (lens.filter) {
        AchievementFilter.ALL -> true
        AchievementFilter.UNLOCKED -> row.unlocked
        AchievementFilter.LOCKED -> !row.unlocked
    } }.sortedWith(lens.sort.comparator())
