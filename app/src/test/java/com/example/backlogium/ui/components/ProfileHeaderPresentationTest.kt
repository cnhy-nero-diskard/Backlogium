package com.example.backlogium.ui.components

import com.example.backlogium.data.repo.LivePresence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileHeaderPresentationTest {

    @Test
    fun runningGameIsNamedAndItsRangeIsMarked() {
        val label = presenceLabel(
            presence = LivePresence.IN_GAME,
            gameName = "Portal",
            showGameName = true,
        )

        requireNotNull(label)
        assertEquals("In game · Portal", label.text)
        assertEquals(label.text.indexOf("Portal"), label.gameNameStart)
    }

    @Test
    fun homeKeepsTheInGameWordsButHidesTheRepeatedTitle() {
        val label = presenceLabel(
            presence = LivePresence.IN_GAME,
            gameName = "Portal",
            showGameName = false,
        )

        assertEquals(PresenceLabel("In game"), label)
    }

    @Test
    fun nonPlayingPresenceHasNoGameNameRange() {
        assertEquals(
            PresenceLabel("Online"),
            presenceLabel(
                presence = LivePresence.ONLINE,
                gameName = "Portal",
                showGameName = true,
            ),
        )
    }

    @Test
    fun unknownPresenceHasNoLabel() {
        assertNull(presenceLabel(LivePresence.UNKNOWN, gameName = "Portal", showGameName = true))
    }
}
