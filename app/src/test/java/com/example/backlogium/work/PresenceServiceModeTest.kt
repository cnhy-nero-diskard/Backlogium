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

    @Test
    fun visibleGameShowsGameNotification() {
        assertEquals(
            PresenceNotificationAction.SHOW_GAME,
            presenceNotificationAction(
                mode = PresenceServiceMode.PLAYING,
                visibleGame = NowPlaying.InGame(gameId = 10L, name = "Portal", iconUrl = null),
            ),
        )
    }

    @Test
    fun hiddenGameShowsMonitoringNotificationNotGameName() {
        assertEquals(
            "a hidden game must not name itself in the notification; the generic monitoring " +
                "shape replaces the game-named notification while the service stays foreground",
            PresenceNotificationAction.MONITORING,
            presenceNotificationAction(
                mode = PresenceServiceMode.PLAYING,
                visibleGame = null,
            ),
        )
    }

    @Test
    fun visibleToHiddenTransitionDropsGameIdentityFromNotification() {
        val game = NowPlaying.InGame(gameId = 10L, name = "Portal", iconUrl = null)
        val visibleAction = presenceNotificationAction(
            mode = PresenceServiceMode.PLAYING,
            visibleGame = game,
        )
        val hiddenAction = presenceNotificationAction(
            mode = PresenceServiceMode.PLAYING,
            visibleGame = null,
        )
        assertEquals(PresenceNotificationAction.SHOW_GAME, visibleAction)
        assertEquals(PresenceNotificationAction.MONITORING, hiddenAction)
    }

    @Test
    fun monitoringModeShowsMonitoringNotification() {
        assertEquals(
            PresenceNotificationAction.MONITORING,
            presenceNotificationAction(mode = PresenceServiceMode.MONITORING, visibleGame = null),
        )
    }

    @Test
    fun stopModeClearsNotification() {
        assertEquals(
            PresenceNotificationAction.CLEAR,
            presenceNotificationAction(mode = PresenceServiceMode.STOP, visibleGame = null),
        )
    }
}
