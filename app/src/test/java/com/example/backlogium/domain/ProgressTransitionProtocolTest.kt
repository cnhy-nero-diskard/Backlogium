package com.example.backlogium.domain

import com.example.backlogium.data.local.entity.PlayerProfile
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Interleaving tests for the persist/recovery protocol.
 *
 * Each one suspends a `persist()` at a named phase and runs a second participant — a recovery pass,
 * a foreground consumer, another `persist()` — while it is stopped there. Without the shared
 * coordinator every one of these is a live race: the state a suspended persist leaves behind is
 * byte-identical to the state a dead one leaves behind, and only ownership of the protocol tells
 * them apart.
 */
class ProgressTransitionProtocolTest {
    private val today = LocalDate.parse("2026-08-13")

    @Test
    fun recoveryCannotClearTheWriteAheadRecordOfALivePersist() = runTest {
        val coordinator = ProgressTransitionCoordinator()
        val gate = WriteAheadGate()
        val marksStore = GatedProgressMarksStore(
            ProgressMarks(lastCelebratedLevel = 4, initialized = true),
            gate::onUpdate,
        )
        val profileDao = FakePlayerProfileDao(PlayerProfile(level = 4))
        val dailyDao = FakeDailyProgressDao(emptyList())
        val updater = testUpdater(marksStore, coordinator, profileDao, dailyDao)

        val persistJob = launch {
            updater.persist(progressResult(today, level = 5), RecomputeSource.SYNC)
        }
        gate.reached.await()

        // The write-ahead record is durable and Room is still untouched: exactly the state an
        // abandoned persist leaves behind, except that this one is alive.
        val walMarks = marksStore.read()
        assertEquals(RecomputeSource.SYNC, walMarks.pendingTransition?.source)
        assertEquals(4, profileDao.get()!!.level)

        val recoveryJob = launch {
            resolvePendingTransition(coordinator, marksStore, profileDao, dailyDao)
        }
        advanceUntilIdle()

        assertFalse("recovery entered the protocol while a persist owned it", recoveryJob.isCompleted)
        assertEquals(walMarks.pendingTransition, marksStore.read().pendingTransition)
        assertEquals(4, marksStore.read().lastCelebratedLevel)

        gate.release.complete(Unit)
        persistJob.join()
        recoveryJob.join()

        // The persist resolved its own transition; recovery then found nothing to resolve, so the
        // earned rise is still owed to a consumer rather than having been consumed by recovery.
        val finalMarks = marksStore.read()
        assertNull(finalMarks.pendingTransition)
        assertEquals(4, finalMarks.lastCelebratedLevel)
        assertEquals(5, profileDao.get()!!.level)
    }

    @Test
    fun consumerSeesNoPhantomEventWhileAPersistIsBetweenItsRoomWriteAndFinalize() = runTest {
        val coordinator = ProgressTransitionCoordinator()
        val roomWritten = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val marksStore = InMemoryProgressMarksStore(
            ProgressMarks(lastCelebratedLevel = 4, initialized = true),
        )
        val backingProfileDao = FakePlayerProfileDao(PlayerProfile(level = 4))
        val profileDao = GatedPlayerProfileDao(backingProfileDao) {
            roomWritten.complete(Unit)
            release.await()
        }
        val dailyDao = FakeDailyProgressDao(emptyList())
        val updater = testUpdater(marksStore, coordinator, profileDao, dailyDao)
        val repository = testRepository(marksStore, coordinator, backingProfileDao, dailyDao)

        // A consumer already collecting — so it cannot be rescued by the recovery pass that runs
        // once at flow start, and must instead refuse to derive from the in-flight pair.
        val emissions = mutableListOf<List<ProgressEvent>>()
        val collectJob = launch { repository.pendingEvents.collect { emissions += it } }
        advanceUntilIdle()
        assertEquals(listOf(emptyList<ProgressEvent>()), emissions)

        val persistJob = launch {
            updater.persist(progressResult(today, level = 24), RecomputeSource.RULE_CHANGE)
        }
        roomWritten.await()
        advanceUntilIdle()

        // Room now says level 24 while the marks still say 4 — a diff of that pair would report a
        // level-up the player never earned, from a rule change that must never produce one.
        assertEquals(24, backingProfileDao.get()!!.level)
        assertEquals(4, marksStore.read().lastCelebratedLevel)
        assertTrue(
            "derived an event from a knowingly half-committed pair: $emissions",
            emissions.all { it.isEmpty() },
        )

        release.complete(Unit)
        persistJob.join()
        advanceUntilIdle()

        // Finalization reseeds the baseline to the values actually written, and derivation resumes.
        val finalMarks = marksStore.read()
        assertNull(finalMarks.pendingTransition)
        assertEquals(24, finalMarks.lastCelebratedLevel)
        assertTrue("a phantom event survived finalization: $emissions", emissions.all { it.isEmpty() })
        collectJob.cancel()
    }

    @Test
    fun concurrentPersistsWithDifferentProvenanceCannotClobberEachOthersRecoveryState() = runTest {
        val coordinator = ProgressTransitionCoordinator()
        val gate = WriteAheadGate()
        val marksStore = GatedProgressMarksStore(
            ProgressMarks(lastCelebratedLevel = 4, initialized = true),
            gate::onUpdate,
        )
        val profileDao = FakePlayerProfileDao(PlayerProfile(level = 4))
        val dailyDao = FakeDailyProgressDao(emptyList())
        val updater = testUpdater(marksStore, coordinator, profileDao, dailyDao)

        val ruleChangeJob = launch {
            updater.persist(progressResult(today, level = 24), RecomputeSource.RULE_CHANGE)
        }
        gate.reached.await()

        val syncJob = launch {
            updater.persist(progressResult(today, level = 30), RecomputeSource.SYNC)
        }
        advanceUntilIdle()

        // The second persist has not entered the protocol at all: the recovery record on file still
        // belongs to the rule change, with the rule change's previous state, and no Room write from
        // the sync has landed. Unserialized, the sync's own record would have replaced it here —
        // attributing the rule change's non-earned write to an earned provenance.
        val inFlight = marksStore.read().pendingTransition
        assertEquals(RecomputeSource.RULE_CHANGE, inFlight?.source)
        assertEquals(4, inFlight?.previousLevel)
        assertEquals(4, profileDao.get()!!.level)

        gate.release.complete(Unit)
        ruleChangeJob.join()
        syncJob.join()

        // Both completed in order: the rule change reseeded the baseline to its own non-earned 24,
        // and the sync's earned rise from 24 to 30 is measured against that — never cleared by it.
        val finalMarks = marksStore.read()
        assertNull(finalMarks.pendingTransition)
        assertEquals(24, finalMarks.lastCelebratedLevel)
        assertEquals(30, profileDao.get()!!.level)

        val repository = testRepository(marksStore, coordinator, profileDao, dailyDao)
        assertEquals(
            listOf(ProgressEvent.LevelUp(24, 30)),
            repository.pendingEvents.first(),
        )
    }

    @Test
    fun pendingEventsDerivesNothingReconstructedWhileATransitionIsInFlight() = runTest {
        // Same suppression, stated directly against the marks: a pending transition marks the
        // Room/marks pair as temporarily non-derivable, whatever the two happen to contain.
        val coordinator = ProgressTransitionCoordinator()
        val marksStore = InMemoryProgressMarksStore(
            ProgressMarks(lastCelebratedLevel = 4, lastCelebratedStreakMilestone = 0, initialized = true),
        )
        val profileDao = FakePlayerProfileDao(PlayerProfile(level = 9, currentStreak = 7))
        val dailyDao = FakeDailyProgressDao(emptyList())
        val repository = testRepository(marksStore, coordinator, profileDao, dailyDao)

        val emissions = mutableListOf<List<ProgressEvent>>()
        val collectJob = launch { repository.pendingEvents.collect { emissions += it } }
        advanceUntilIdle()

        // With no transition in flight the pair is derivable, and both reconstructed events appear.
        assertEquals(
            listOf(ProgressEvent.LevelUp(4, 9), ProgressEvent.StreakMilestone(7)),
            emissions.last(),
        )

        marksStore.update {
            it.copy(
                pendingTransition = PendingTransition(
                    source = RecomputeSource.SYNC,
                    previousLevel = 4,
                    previousStreak = 0,
                    previousTodayQuestMet = false,
                    evaluationDate = today,
                ),
            )
        }
        advanceUntilIdle()
        assertEquals(emptyList<ProgressEvent>(), emissions.last())

        // ...and resumes once it is cleared.
        marksStore.update { it.copy(pendingTransition = null) }
        advanceUntilIdle()
        assertEquals(
            listOf(ProgressEvent.LevelUp(4, 9), ProgressEvent.StreakMilestone(7)),
            emissions.last(),
        )
        collectJob.cancel()
    }
}
