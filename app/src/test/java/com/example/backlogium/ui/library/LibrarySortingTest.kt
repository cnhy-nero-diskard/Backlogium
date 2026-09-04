package com.example.backlogium.ui.library

import com.example.backlogium.domain.LibrarySortDirection
import com.example.backlogium.domain.LibrarySortKey
import com.example.backlogium.domain.LibrarySortPrefs
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

    @Test
    fun activeSearchRanksTiersBeforeApplyingTheChosenSortWithinEachTier() {
        val sorted = listOf(
            row("Hundred Hours", playtimeForever = 10_000),
            row("Red Beta", playtimeForever = 200),
            row("Red Alpha", playtimeForever = 100),
        ).sortedFor(LibrarySortKey.PLAYTIME, query = "red")

        assertEquals(listOf("Red Beta", "Red Alpha", "Hundred Hours"), sorted.names())
    }

    /**
     * Reversal is total, not partial: the reversed list is the exact reverse of the default one,
     * tie-break included. Anything less means two rows equal on the key could sit in the same
     * relative order in both directions, which reads as a list that did not fully turn around.
     */
    @Test
    fun everyKeyReversedIsTheExactReverseOfItsDefaultOrder() {
        // Deliberately contains ties on every key so the tie-break participates in the reversal.
        val games = listOf(
            row("Zeta", playtimeForever = 600, playtime2Weeks = 10, xpContributed = 50),
            row("Alpha", playtimeForever = 600, playtime2Weeks = 0, xpContributed = 50),
            row("Mu", playtimeForever = 60, playtime2Weeks = 10, xpContributed = 0),
            row("Beta", playtimeForever = 6_000, playtime2Weeks = 0, xpContributed = 900),
        )

        LibrarySortKey.entries.forEach { key ->
            val default = games.sortedFor(key, key.defaultDirection).names()
            val reversed = games.sortedFor(key, key.defaultDirection.flipped()).names()

            assertEquals("reversal under $key", default.reversed(), reversed)
        }
    }

    /**
     * The one place in this feature where getting it wrong yields a wrong answer rather than an
     * unexpected order: reversing must not invert search relevance, or a query would rank its
     * *weakest* match first.
     */
    @Test
    fun reversalLeavesSearchRelevanceAscendingAndReversesOnlyWithinATier() {
        val games = listOf(
            row("Hundred Hours", playtimeForever = 10_000),
            row("Red Beta", playtimeForever = 200),
            row("Red Alpha", playtimeForever = 100),
        )

        val default = games.sortedFor(LibrarySortKey.PLAYTIME, query = "red").names()
        assertEquals(listOf("Red Beta", "Red Alpha", "Hundred Hours"), default)

        val reversed = games
            .sortedFor(
                key = LibrarySortKey.PLAYTIME,
                direction = LibrarySortDirection.ASCENDING,
                query = "red",
            )
            .names()

        // The weaker "Hundred Hours" match stays last; only the two equally strong matches swap.
        assertEquals(listOf("Red Alpha", "Red Beta", "Hundred Hours"), reversed)
    }

    @Test
    fun gamesMissingTheKeySortFirstWhenReversed() {
        val recent = listOf(
            row("Never recently played", playtime2Weeks = 0),
            row("Active", playtime2Weeks = 10),
        ).sortedFor(LibrarySortKey.RECENT_ACTIVITY, LibrarySortDirection.ASCENDING)
        assertEquals(listOf("Never recently played", "Active"), recent.names())

        val xp = listOf(
            row("No XP", xpContributed = 0),
            row("Some XP", xpContributed = 1),
        ).sortedFor(LibrarySortKey.XP_CONTRIBUTED, LibrarySortDirection.ASCENDING)
        assertEquals(listOf("No XP", "Some XP"), xp.names())
    }

    /**
     * The migration-free promise: a list whose direction was never stored reads its key's default
     * and reproduces the ordering the Library had before directions existed — `name ASC` for
     * [LibrarySortKey.NAME], value-descending with a name-ascending tie-break for the other three.
     */
    @Test
    fun absentDirectionReproducesThePreChangeOrderingForEveryKey() {
        val prefs = LibrarySortPrefs()
        assertEquals(LibrarySortDirection.ASCENDING, prefs.focus.defaultDirection)
        assertEquals(LibrarySortDirection.DESCENDING, prefs.library.defaultDirection)
        assertEquals(prefs.focus.defaultDirection, prefs.focusDirection)
        assertEquals(prefs.library.defaultDirection, prefs.libraryDirection)

        val games = listOf(
            row("Zeta", playtimeForever = 600, playtime2Weeks = 5, xpContributed = 20),
            row("Alpha", playtimeForever = 600, playtime2Weeks = 5, xpContributed = 20),
            row("Beta", playtimeForever = 6_000, playtime2Weeks = 50, xpContributed = 200),
        )
        val valueDescendingThenNameAscending = listOf("Beta", "Alpha", "Zeta")

        assertEquals(
            listOf("Alpha", "Beta", "Zeta"),
            games.sortedFor(LibrarySortKey.NAME, LibrarySortKey.NAME.defaultDirection).names(),
        )
        listOf(
            LibrarySortKey.PLAYTIME,
            LibrarySortKey.RECENT_ACTIVITY,
            LibrarySortKey.XP_CONTRIBUTED,
        ).forEach { key ->
            assertEquals(
                "default direction for $key",
                valueDescendingThenNameAscending,
                games.sortedFor(key, key.defaultDirection).names(),
            )
            // And omitting the direction entirely is the same thing.
            assertEquals(
                "omitted direction for $key",
                games.sortedFor(key, key.defaultDirection).names(),
                games.sortedFor(key).names(),
            )
        }
    }

    private fun List<LibraryRow>.names() = map { it.name }

    private fun row(
        name: String,
        appId: Long = name.hashCode().toLong(),
        playtimeForever: Int = 0,
        playtime2Weeks: Int = 0,
        xpContributed: Long = 0L,
    ) = TestRow(appId, name, playtimeForever, playtime2Weeks, xpContributed)

    private data class TestRow(
        override val appId: Long,
        override val name: String,
        override val playtimeForever: Int,
        override val playtime2Weeks: Int,
        override val xpContributed: Long,
        override val genres: List<GameGenre> = emptyList(),
    ) : LibraryRow
}
