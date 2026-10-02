package com.example.backlogium.ui.gamedetail

import androidx.lifecycle.SavedStateHandle
import com.example.backlogium.data.repo.GameAchievement
import com.example.backlogium.gamification.RuleConfig
import org.junit.Assert.*
import org.junit.Test

class DetailVisitStateTest {
    @Test fun recreationRetainsLensAndNewVisitResetsBoth() {
        val saved = SavedStateHandle()
        val visit = DetailVisitState(saved)
        visit.open("first")
        visit.setSort(AchievementSort.RARITY)
        visit.setFilter(AchievementFilter.LOCKED)
        val recreated = DetailVisitState(SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
        recreated.open("first")
        assertEquals(AchievementLens(AchievementSort.RARITY, AchievementFilter.LOCKED), recreated.lens.value)
        recreated.open("second")
        assertEquals(AchievementLens(), recreated.lens.value)
    }

    @Test fun filteringPreservesSortAndDoesNotAlterFullSummaryOrCompletion() {
        val rows = listOf(
            GameAchievement("earned", "Earned", null, true, 0.8, 20.0),
            GameAchievement("locked", "Locked", null, false, null, 1.0),
        ).map { it.toUi(RuleConfig()) }
        assertEquals(listOf("locked", "earned"), rows.visibleThrough(AchievementLens(AchievementSort.RARITY)).map { it.apiName })
        assertEquals(listOf("earned"), rows.visibleThrough(AchievementLens(AchievementSort.RARITY, AchievementFilter.UNLOCKED)).map { it.apiName })
        val state = GameDetailUiState(summary = GameSummaryUi(achievementsUnlocked = 1, achievementsTotal = 2),
            achievements = rows.visibleThrough(AchievementLens(filter = AchievementFilter.UNLOCKED)))
        assertFalse(state.allUnlocked)
        assertEquals(2, state.summary.achievementsTotal)
        val complete = state.copy(summary = GameSummaryUi(achievementsUnlocked = 2, achievementsTotal = 2),
            achievements = emptyList(), filter = AchievementFilter.LOCKED)
        assertTrue(complete.allUnlocked)
    }
}
