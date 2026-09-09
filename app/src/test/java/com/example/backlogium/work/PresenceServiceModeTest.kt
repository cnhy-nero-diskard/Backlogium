package com.example.backlogium.work

import com.example.backlogium.data.repo.NowPlaying
import org.junit.Assert.assertEquals
import org.junit.Test

class PresenceServiceModeTest {

    @Test
    fun idleMonitorKeepsTheServiceAlive() {
        assertEquals(
            PresenceServiceMode.MONITORING,
            presenceServiceMode(liveMonitorEnabled = true, rawNowPlaying = NowPlaying.NotPlaying),
        )
    }

    @Test
    fun idleServiceStopsWhenMonitorIsDisabled() {
        assertEquals(
            PresenceServiceMode.STOP,
            presenceServiceMode(liveMonitorEnabled = false, rawNowPlaying = NowPlaying.NotPlaying),
        )
    }

    @Test
    fun activeGameSurvivesMonitorBeingDisabled() {
        assertEquals(
            PresenceServiceMode.PLAYING,
            presenceServiceMode(
                liveMonitorEnabled = false,
                rawNowPlaying = NowPlaying.InGame(gameId = 10L, name = "Portal", iconUrl = null),
            ),
        )
    }

    @Test
    fun hiddenGameKeepsTheServiceAliveForSessionRecording() {
        assertEquals(
            "a hidden game's raw signal is InGame, so the service stays alive",
            PresenceServiceMode.PLAYING,
            presenceServiceMode(
                liveMonitorEnabled = false,
                rawNowPlaying = NowPlaying.InGame(gameId = 10L, name = "", iconUrl = null),
            ),
        )
    }
}
