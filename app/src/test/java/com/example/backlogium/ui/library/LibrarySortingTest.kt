package com.example.backlogium.ui.library

import com.example.backlogium.domain.LibrarySortKey
import org.junit.Assert.assertEquals
import org.junit.Test
import com.example.backlogium.data.repo.GameGenre

/**
 * The four Library sort keys: each one's direction, its name tie-break, and where games with no
 * value for the key land.
 *
 * The tie-break is the part worth pinning: without it, two games equal on the active key would
 * fall back to whatever order Room returned, which is not a defined order at all.
 */
class LibrarySortingTest {

    @Test
    fun playtime_ordersLongestFirst() {
        val sorted = listOf(
            row("Short", playtimeForever = 60),
            row("Long", playtimeForever = 6_000),
            row("Middling", playtimeForever = 600),
        ).sortedFor(LibrarySortKey.PLAYTIME)

        assertEquals(listOf("Long", "Middling", "Short"), sorted.names())
    }

    @Test
    fun name_ordersAscending() {
        val sorted = listOf(row("Portal"), row("Alan Wake"), row("Hades"))
            .sortedFor(LibrarySortKey.NAME)

        assertEquals(listOf("Alan Wake", "Hades", "Portal"), sorted.names())
    }

    @Test
    fun recentActivity_ordersByTwoWeekPlaytime_notLifetime() {
        // The two keys genuinely disagree: the 500-hour game has not been touched in a fortnight.
        val sorted = listOf(
            row("Retired favourite", playtimeForever = 30_000, playtime2Weeks = 0),
            row("Playing now", playtimeForever = 120, playtime2Weeks = 400),
        ).sortedFor(LibrarySortKey.RECENT_ACTIVITY)

        assertEquals(listOf("Playing now", "Retired favourite"), sorted.names())
    }

    @Test
    fun xpContributed_ordersHighestFirst() {
        val sorted = listOf(
            row("Small", xpContributed = 40),
            row("Big", xpContributed = 4_000),
        ).sortedFor(LibrarySortKey.XP_CONTRIBUTED)

        assertEquals(listOf("Big", "Small"), sorted.names())
    }

    @Test
    fun everyKeyTieBreaksByNameAscending() {
        // Equal on each key, deliberately supplied out of name order.
        val tied = listOf(row("Zeta"), row("Alpha"), row("Mu"))
        val expected = listOf("Alpha", "Mu", "Zeta")

        LibrarySortKey.entries.forEach { key ->
            assertEquals("tie-break under $key", expected, tied.sortedFor(key).names())
        }
    }

    @Test
    fun gamesMissingTheKeySortLast() {
        // "Missing" is zero for both derived keys: never played recently, never earned XP. Those
        // rows must be present and last, not dropped and not interleaved.
        val recent = listOf(
            row("Never recently played", playtime2Weeks = 0),
            row("Active", playtime2Weeks = 10),
        ).sortedFor(LibrarySortKey.RECENT_ACTIVITY)
        assertEquals(listOf("Active", "Never recently played"), recent.names())

        val xp = listOf(
            row("No XP", xpContributed = 0),
            row("Some XP", xpContributed = 1),
        ).sortedFor(LibrarySortKey.XP_CONTRIBUTED)
        assertEquals(listOf("Some XP", "No XP"), xp.names())
    }

    @Test
    fun identicalNamesStillOrderDeterministically() {
        val sorted = listOf(
            row("Duplicate", appId = 20L),
            row("Duplicate", appId = 10L),
        ).sortedFor(LibrarySortKey.NAME)

        assertEquals(listOf(10L, 20L), sorted.map { it.appId })
    }

    private fun List<LibraryRow>.names() = map { it.name }

    private fun row(
        name: String,
        appId: Long = name.hashCode().toLong(),
        playtimeForever: Int = 0,
        playtime2Weeks: Int = 0,
        xpContributed: Int = 0,
    ) = TestRow(appId, name, playtimeForever, playtime2Weeks, xpContributed)

    private data class TestRow(
        override val appId: Long,
        override val name: String,
        override val playtimeForever: Int,
        override val playtime2Weeks: Int,
        override val xpContributed: Int,
        override val genres: List<GameGenre> = emptyList(),
    ) : LibraryRow
}
