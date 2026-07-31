package com.example.backlogium.ui.gamedetail

import com.example.backlogium.gamification.RarityTier
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The achievement list's two orderings (enhance-game-detail).
 *
 * The rule worth pinning down is that locked achievements group *after* unlocked ones in both
 * modes. Locked rows have no unlock date at all, and their percent answers a different question
 * ("how rare is this" rather than "how rare was mine"), so interleaving them by a null-ish key
 * produces an order that reads as arbitrary — the kind of thing a user reports as a bug.
 */
class AchievementSortTest {

    @Test
    fun byDate_mostRecentFirst() {
        val sorted = listOf(
            unlocked("old", at = 1_000L),
            unlocked("newest", at = 3_000L),
            unlocked("middle", at = 2_000L),
        ).sortedWith(AchievementSort.DATE_ACHIEVED.comparator())

        assertEquals(listOf("newest", "middle", "old"), sorted.names())
    }

    @Test
    fun byRarity_rarestFirst() {
        // Rarest means the *lowest* share of players, so 0.8% leads and 60% trails.
        val sorted = listOf(
            unlocked("common", percent = 60.0),
            unlocked("legendary", percent = 0.8),
            unlocked("rare", percent = 12.0),
        ).sortedWith(AchievementSort.RARITY.comparator())

        assertEquals(listOf("legendary", "rare", "common"), sorted.names())
    }

    @Test
    fun byDate_lockedGroupAfterUnlocked() {
        val sorted = listOf(
            locked("locked-a"),
            unlocked("unlocked-old", at = 1_000L),
            locked("locked-b"),
            unlocked("unlocked-new", at = 2_000L),
        ).sortedWith(AchievementSort.DATE_ACHIEVED.comparator())

        assertEquals(
            listOf("unlocked-new", "unlocked-old", "locked-a", "locked-b"),
            sorted.names(),
        )
    }

    @Test
    fun byRarity_lockedGroupAfterUnlocked_evenWhenRarer() {
        // The locked one is by far the rarest achievement here; it still sorts after every
        // unlocked row rather than leading the list.
        val sorted = listOf(
            unlocked("unlocked-common", percent = 50.0),
            locked("locked-ultra-rare", percent = 0.1),
            unlocked("unlocked-rare", percent = 5.0),
        ).sortedWith(AchievementSort.RARITY.comparator())

        assertEquals(
            listOf("unlocked-rare", "unlocked-common", "locked-ultra-rare"),
            sorted.names(),
        )
    }

    @Test
    fun byRarity_locked_areOrderedAmongThemselvesByTheirGlobalPercent() {
        val sorted = listOf(
            locked("locked-common", percent = 40.0),
            locked("locked-rare", percent = 2.0),
        ).sortedWith(AchievementSort.RARITY.comparator())

        assertEquals(listOf("locked-rare", "locked-common"), sorted.names())
    }

    @Test
    fun byRarity_unknownPercentSortsLastWithinItsGroup() {
        val sorted = listOf(
            unlocked("unknown", percent = null),
            unlocked("known", percent = 30.0),
        ).sortedWith(AchievementSort.RARITY.comparator())

        assertEquals(listOf("known", "unknown"), sorted.names())
    }

    @Test
    fun byDate_missingDateSortsLastWithinItsGroup() {
        // An unlocked row with no stored timestamp: possible for older data, and it must not
        // displace rows that do have one.
        val sorted = listOf(
            unlocked("no-date", at = null),
            unlocked("dated", at = 500L),
        ).sortedWith(AchievementSort.DATE_ACHIEVED.comparator())

        assertEquals(listOf("dated", "no-date"), sorted.names())
    }

    @Test
    fun allLocked_staysStableByName() {
        val sorted = listOf(locked("charlie"), locked("alpha"), locked("bravo"))
            .sortedWith(AchievementSort.DATE_ACHIEVED.comparator())

        assertEquals(listOf("alpha", "bravo", "charlie"), sorted.names())
    }

    @Test
    fun allUnlocked_ordersByTheChosenKeyOnly() {
        val rows = listOf(
            unlocked("a", at = 1_000L, percent = 5.0),
            unlocked("b", at = 2_000L, percent = 50.0),
        )

        assertEquals(listOf("b", "a"), rows.sortedWith(AchievementSort.DATE_ACHIEVED.comparator()).names())
        assertEquals(listOf("a", "b"), rows.sortedWith(AchievementSort.RARITY.comparator()).names())
    }

    @Test
    fun tiesFallBackToName_soTheOrderIsNeverArbitrary() {
        val sorted = listOf(
            unlocked("zulu", at = 1_000L),
            unlocked("alpha", at = 1_000L),
        ).sortedWith(AchievementSort.DATE_ACHIEVED.comparator())

        assertEquals(listOf("alpha", "zulu"), sorted.names())
    }

    @Test
    fun emptyList_sortsWithoutError() {
        assertEquals(
            emptyList<String>(),
            emptyList<AchievementUi>().sortedWith(AchievementSort.RARITY.comparator()).names(),
        )
    }

    private fun List<AchievementUi>.names() = map { it.displayName }

    private fun unlocked(
        name: String,
        at: Long? = 1_000L,
        percent: Double? = 10.0,
    ) = row(name, unlocked = true, at = at, percent = percent)

    private fun locked(name: String, percent: Double? = null) =
        row(name, unlocked = false, at = null, percent = percent)

    private fun row(
        name: String,
        unlocked: Boolean,
        at: Long?,
        percent: Double?,
    ) = AchievementUi(
        apiName = name,
        displayName = name,
        iconUrl = null,
        unlocked = unlocked,
        tier = if (unlocked && percent != null) RarityTier.COMMON else null,
        xp = 0,
        unlockPercent = percent,
        unlockedAt = at,
        description = null,
        hidden = false,
    )
}
