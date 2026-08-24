package com.example.backlogium.ui.home

import com.example.backlogium.data.local.AcquiredGamesAnnouncement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The announcement's whole lifecycle: shown, dismissed, superseded, and expired by time alone.
 *
 * Expiry is the case worth testing hardest, because it is the one with no event behind it — nothing
 * writes to retire the banner, so if the arithmetic is wrong there is no other mechanism that would
 * eventually clear it.
 */
class AcquiredGamesAnnouncementTest {

    private val now = 1_700_000_000_000L
    private val hour = 60L * 60 * 1_000

    private val names = mapOf(
        1L to "Hades",
        2L to "Celeste",
        3L to "Hollow Knight",
        4L to "Outer Wilds",
        5L to "Tunic",
    )

    private fun announcement(
        appIds: Set<Long>,
        acquiredAt: Long = now,
        dismissed: Boolean = false,
    ) = AcquiredGamesAnnouncement(appIds, acquiredAt, dismissed)

    @Test
    fun `a fresh batch is presented and names its games`() {
        val ui = announcement(setOf(1L, 2L)).toUi(names, now)!!
        assertEquals(listOf("Celeste", "Hades"), ui.namedGames)
        assertEquals(0, ui.unnamedCount)
        assertEquals(2, ui.totalCount)
    }

    @Test
    fun `a large batch names some and counts the rest`() {
        val ui = announcement(setOf(1L, 2L, 3L, 4L, 5L)).toUi(names, now)!!
        assertEquals(3, ui.namedGames.size)
        assertEquals(2, ui.unnamedCount)
        assertEquals(5, ui.totalCount)
    }

    @Test
    fun `a game the library cannot name is still counted`() {
        // The count is the announcement's load-bearing claim; dropping an unnamed arrival would
        // make the banner understate what actually happened.
        val ui = announcement(setOf(1L, 99L)).toUi(names, now)!!
        assertEquals(listOf("Hades"), ui.namedGames)
        assertEquals(1, ui.unnamedCount)
        assertEquals(2, ui.totalCount)
    }

    @Test
    fun `a dismissed batch is not presented`() {
        assertNull(announcement(setOf(1L), dismissed = true).toUi(names, now))
    }

    @Test
    fun `an empty batch is not presented`() {
        assertNull(announcement(emptySet()).toUi(names, now))
    }

    @Test
    fun `a batch expires by time alone`() {
        val acquiredAt = now - 23 * hour
        assertEquals(1, announcement(setOf(1L), acquiredAt).toUi(names, now)!!.totalCount)
        // One hour later, with nothing having run in between and no write having occurred.
        assertNull(announcement(setOf(1L), acquiredAt).toUi(names, now + 2 * hour))
    }

    @Test
    fun `a later acquisition supersedes a dismissed earlier one`() {
        // The store replaces rather than accumulates, and clears the flag with it: buying more
        // games is new information, and a dismissal twenty hours ago was about different games.
        val dismissedEarlier = announcement(setOf(1L), now - 20 * hour, dismissed = true)
        assertNull(dismissedEarlier.toUi(names, now))

        val superseding = AcquiredGamesAnnouncement(setOf(3L), now, dismissed = false)
        assertEquals(listOf("Hollow Knight"), superseding.toUi(names, now)!!.namedGames)
    }

    @Test
    fun `the title leads with the count`() {
        assertEquals("1 new game", acquiredGamesTitle(1))
        assertEquals("8 new games", acquiredGamesTitle(8))
    }

    @Test
    fun `the detail names what it can and counts what it cannot`() {
        assertEquals(
            "Hades — added to your library.",
            acquiredGamesDetail(AcquiredGamesUi(listOf("Hades"), unnamedCount = 0)),
        )
        assertEquals(
            "Hades, Celeste and 5 more — added to your library.",
            acquiredGamesDetail(AcquiredGamesUi(listOf("Hades", "Celeste"), unnamedCount = 5)),
        )
        assertEquals(
            "Added to your library.",
            acquiredGamesDetail(AcquiredGamesUi(emptyList(), unnamedCount = 3)),
        )
    }
}
