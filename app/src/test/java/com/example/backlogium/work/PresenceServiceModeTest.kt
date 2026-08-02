package com.example.backlogium.work

import com.example.backlogium.data.repo.NowPlaying
import org.junit.Assert.assertEquals
import org.junit.Test

class PresenceServiceModeTest {

    @Test
    fun idleMonitorKeepsTheServiceAlive() {
        assertEquals(
            PresenceServiceMode.MONITORING,
            presenceServiceMode(liveMonitorEnabled = true, nowPlaying = NowPlaying.NotPlaying),
        )
    }

    @Test
    fun idleServiceStopsWhenMonitorIsDisabled() {
        assertEquals(
            PresenceServiceMode.STOP,
            presenceServiceMode(liveMonitorEnabled = false, nowPlaying = NowPlaying.NotPlaying),
        )
    }

    @Test
    fun activeGameSurvivesMonitorBeingDisabled() {
        assertEquals(
            PresenceServiceMode.PLAYING,
            presenceServiceMode(
                liveMonitorEnabled = false,
                nowPlaying = NowPlaying.InGame(gameId = 10L, name = "Portal", iconUrl = null),
            ),
        )
    }
}
