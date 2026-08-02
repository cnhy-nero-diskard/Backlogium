package com.example.backlogium

import com.example.backlogium.data.repo.LivePresence
import com.example.backlogium.data.repo.LiveStatus
import com.example.backlogium.data.repo.NowPlaying
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundPresenceDetectionTest {

    @Test
    fun retriesUntilSteamReportsTheRunningGame() = runTest {
        val observations = ArrayDeque(
            listOf(
                LiveStatus(nowPlaying = NowPlaying.NotPlaying),
                LiveStatus(nowPlaying = NowPlaying.NotPlaying),
                inGameStatus(),
            ),
        )
        var starts = 0
        var delays = 0

        val detected = detectForegroundPresence(
            checkNow = { observations.removeFirst() },
            startPresence = { starts++ },
            delayBeforeRetry = { delays++ },
        )

        assertTrue(detected)
        assertEquals(1, starts)
        assertEquals(2, delays)
    }

    @Test
    fun stopsImmediatelyWhenTheFirstCheckDetectsTheGame() = runTest {
        var checks = 0
        var delays = 0

        val detected = detectForegroundPresence(
            checkNow = {
                checks++
                inGameStatus()
            },
            startPresence = {},
            delayBeforeRetry = { delays++ },
        )

        assertTrue(detected)
        assertEquals(1, checks)
        assertEquals(0, delays)
    }

    @Test
    fun givesUpAfterTheBoundedAttemptCount() = runTest {
        var checks = 0
        var starts = 0
        var delays = 0

        val detected = detectForegroundPresence(
            checkNow = {
                checks++
                LiveStatus(nowPlaying = NowPlaying.NotPlaying)
            },
            startPresence = { starts++ },
            attempts = 4,
            delayBeforeRetry = { delays++ },
        )

        assertFalse(detected)
        assertEquals(4, checks)
        assertEquals(0, starts)
        assertEquals(3, delays)
    }

    @Test
    fun cancellationPreventsALateRetainedStatusFromStartingPresence() = runTest {
        var starts = 0
        val detection = launch {
            detectForegroundPresence(
                checkNow = {
                    try {
                        awaitCancellation()
                    } catch (_: CancellationException) {
                        // Mirrors LiveStatusRepository.checkNow retaining its prior value when
                        // runCatching turns cancellation into a failed observation.
                        inGameStatus()
                    }
                },
                startPresence = { starts++ },
                delayBeforeRetry = {},
            )
        }
        runCurrent()

        detection.cancelAndJoin()

        assertEquals(0, starts)
    }

    private fun inGameStatus() = LiveStatus(
        nowPlaying = NowPlaying.InGame(gameId = 10L, name = "Portal", iconUrl = null),
        presence = LivePresence.IN_GAME,
    )
}
