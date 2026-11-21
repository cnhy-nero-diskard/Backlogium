package com.example.backlogium.data.local

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import com.example.backlogium.domain.PlaytimeRecoveryStatus

@RunWith(RobolectricTestRunner::class)
class RecoveryReadStatusStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun persistsOutcomesAndKeepsObservedPlayAwaitingUntilSteamQuantityAdvances() = runTest {
        val store = RecoveryReadStatusStore(context)
        val account = "account-a-${java.util.UUID.randomUUID()}"
        store.successfulRead(account, observedAt = 100L)
        assertEquals(PlaytimeRecoveryStatus.UNCHANGED_NO_KNOWN_PLAY, store.observe(account).first())

        store.observedPlay(account, appId = 440L, observedAt = 150L)
        assertEquals(PlaytimeRecoveryStatus.OBSERVED_AWAITING_STEAM, store.observe(account).first())

        // An intervening unchanged Steam poll proves only that no quantity has landed yet; it does
        // not erase the retained local play cue or imply the game was unplayed.
        store.successfulRead(account, observedAt = 200L)
        val restarted = RecoveryReadStatusStore(context)
        assertEquals(PlaytimeRecoveryStatus.OBSERVED_AWAITING_STEAM, restarted.observe(account).first())

        restarted.unavailable(account, observedAt = 250L)
        assertEquals(PlaytimeRecoveryStatus.REMOTE_UNAVAILABLE, store.observe(account).first())
        restarted.successfulRead(account, observedAt = 300L, creditedIncreaseAppIds = setOf(440L))
        assertEquals(PlaytimeRecoveryStatus.KNOWN_GOOD, store.observe(account).first())

        restarted.observedPlay(account, appId = 570L, observedAt = 500L)
        // A quantity increase whose reading predates this newer observed session cannot resolve it.
        restarted.successfulRead(account, observedAt = 450L, creditedIncreaseAppIds = setOf(440L))
        assertEquals(PlaytimeRecoveryStatus.OBSERVED_AWAITING_STEAM, store.observe(account).first())
        restarted.successfulRead(account, observedAt = 550L, creditedIncreaseAppIds = setOf(570L))
        assertEquals(PlaytimeRecoveryStatus.KNOWN_GOOD, store.observe(account).first())

        // A late failed response cannot rewind a newer successful outcome.
        restarted.unavailable(account, observedAt = 475L)
        assertEquals(PlaytimeRecoveryStatus.KNOWN_GOOD, store.observe(account).first())
    }

    @Test
    fun anotherAccountCannotReadThePriorAccountsDisclosureState() = runTest {
        val store = RecoveryReadStatusStore(context)
        val account = "account-a-${java.util.UUID.randomUUID()}"
        store.unavailable(account, observedAt = 100L)
        assertEquals(PlaytimeRecoveryStatus.REMOTE_UNAVAILABLE, store.observe(account).first())
        assertEquals(PlaytimeRecoveryStatus.KNOWN_GOOD, store.observe("account-b-${java.util.UUID.randomUUID()}").first())
    }

    @Test
    fun firstReadingBaselinesLifetimeWithoutLeavingAFalsePendingPlayWarning() = runTest {
        val account = "baseline-account-${java.util.UUID.randomUUID()}"
        val store = RecoveryReadStatusStore(context)
        store.observedPlay(account, appId = 440L, observedAt = 100L)
        store.successfulRead(
            account = account,
            observedAt = 200L,
            baselineEstablishedAppIds = setOf(440L),
        )
        assertEquals(PlaytimeRecoveryStatus.KNOWN_GOOD, store.observe(account).first())
    }
}
