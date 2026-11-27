package com.example.backlogium.data.repo

import com.example.backlogium.domain.PlaytimeAllocationMethod
import com.example.backlogium.domain.PlaytimeReadProjector
import com.example.backlogium.domain.RecipeCredit
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class InsightRecipeBoundaryTest {
    @Test fun boundariesMatchCanonicalPositiveSharesAcrossRemaindersExclusionsAndMaterialization() {
        val start = LocalDate.of(2020, 1, 1)
        for (base in listOf(0L, 2L)) for (remainder in listOf(0L, 1L, 8L)) {
            val recipe = RecipeCredit("fixture", 1, PlaytimeAllocationMethod.MULTI_DAY_ESTIMATE,
                start, start.plusDays(99), base, remainder, 98,
                listOf(start.plusDays(1), start.plusDays(4)), null)
            for (cursor in listOf(null, start.plusDays(2), start.plusDays(98))) {
                val resumed = recipe.copy(materializedThroughDate = cursor)
                for (offset in listOf(0L, 1L, 5L, 50L, 101L)) {
                    val before = start.plusDays(offset)
                    val expected = PlaytimeReadProjector.recipeActivities(listOf(resumed), start, before)
                        .map { it.date }
                    val actual = positiveRecipeBoundsBefore(resumed, before)
                    assertEquals(expected.firstOrNull(), actual?.first)
                    assertEquals(expected.lastOrNull(), actual?.last)
                }
            }
        }
    }
}
