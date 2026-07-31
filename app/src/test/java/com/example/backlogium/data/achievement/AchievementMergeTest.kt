package com.example.backlogium.data.achievement

import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.remote.dto.AchievementSchemaDto
import com.example.backlogium.data.remote.dto.PlayerAchievementDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merge rule's two promises: the rarity snapshot is frozen at first unlock and never moves
 * again (the add-steam-achievements rarity-drift policy, which tier/XP depend on), and the schema's
 * text fields follow last-known-good so a blank incoming value never erases stored text
 * (enhance-game-detail).
 */
class AchievementMergeTest {

    private val appId = 440L

    @Test
    fun descriptionAndHiddenAreCarriedFromTheSchema() {
        val row = merge(
            dto = unlocked(),
            schema = schema(description = "Kill 10 enemies", hidden = 0),
        )

        assertEquals("Kill 10 enemies", row.description)
        assertFalse(row.hidden)
    }

    @Test
    fun hiddenFlagIsReadAsSteamsIntNotAsTruthiness() {
        val row = merge(dto = unlocked(), schema = schema(hidden = 1))

        assertTrue(row.hidden)
    }

    @Test
    fun aBlankIncomingDescriptionKeepsTheStoredOne() {
        // Steam withholds a hidden achievement's description until it is unlocked, so a later sync
        // can legitimately carry a blank one for a row that already has text.
        val row = merge(
            dto = unlocked(),
            schema = schema(description = "", hidden = 1),
            prior = stored(description = "Finish the game without dying", hidden = true),
        )

        assertEquals("Finish the game without dying", row.description)
    }

    @Test
    fun aMissingSchemaKeepsTheStoredDescriptionAndHiddenFlag() {
        // The schema fetch is best-effort in the repository; a failed one must not blank the row.
        val row = merge(
            dto = unlocked(),
            schema = null,
            prior = stored(description = "Reach level 50", hidden = true),
        )

        assertEquals("Reach level 50", row.description)
        assertTrue(row.hidden)
    }

    @Test
    fun descriptionIsNullWhenNeitherSchemaNorPriorHasOne() {
        val row = merge(dto = unlocked(), schema = null, prior = null)

        assertNull(row.description)
        assertFalse(row.hidden)
    }

    @Test
    fun snapshotIsCapturedFromTheGlobalPercentAtFirstUnlock() {
        val row = merge(dto = unlocked(), globalPercent = 0.8, prior = null)

        assertEquals(0.8, row.snapshotPercent!!, 0.0001)
    }

    @Test
    fun snapshotIsNeverOverwrittenOnceSet() {
        // The whole point of the policy: the live percent has drifted from 0.8 to 6.0, but the row
        // keeps the percent that earned its tier and XP.
        val row = merge(
            dto = unlocked(),
            globalPercent = 6.0,
            schema = schema(description = "Now with a description"),
            prior = stored(snapshotPercent = 0.8),
        )

        assertEquals(0.8, row.snapshotPercent!!, 0.0001)
        assertEquals(6.0, row.globalPercent!!, 0.0001)
        // Merging new text alongside must not have disturbed the snapshot.
        assertEquals("Now with a description", row.description)
    }

    @Test
    fun aLockedAchievementCapturesNoSnapshot() {
        val row = merge(dto = locked(), globalPercent = 42.0, prior = null)

        assertNull(row.snapshotPercent)
        assertEquals(42.0, row.globalPercent!!, 0.0001)
    }

    private fun merge(
        dto: PlayerAchievementDto,
        globalPercent: Double? = null,
        schema: AchievementSchemaDto? = null,
        prior: Achievement? = null,
        now: Long = 1_000L,
    ) = AchievementMerge.merge(
        appId = appId,
        dto = dto,
        globalPercent = globalPercent,
        schema = schema,
        prior = prior,
        now = now,
    )

    private fun unlocked() =
        PlayerAchievementDto(apiName = "ACH_WIN", achieved = 1, unlocktime = 1_700_000_000L)

    private fun locked() = PlayerAchievementDto(apiName = "ACH_WIN", achieved = 0)

    private fun schema(description: String = "", hidden: Int = 0) = AchievementSchemaDto(
        name = "ACH_WIN",
        displayName = "Winner",
        icon = "https://example.invalid/ach.jpg",
        description = description,
        hidden = hidden,
    )

    private fun stored(
        description: String? = null,
        hidden: Boolean = false,
        snapshotPercent: Double? = null,
    ) = Achievement(
        appId = appId,
        apiName = "ACH_WIN",
        displayName = "Winner",
        unlocked = true,
        unlockedAt = 1_700_000_000L,
        globalPercent = snapshotPercent,
        snapshotPercent = snapshotPercent,
        description = description,
        hidden = hidden,
        fetchedAt = 500L,
    )
}
