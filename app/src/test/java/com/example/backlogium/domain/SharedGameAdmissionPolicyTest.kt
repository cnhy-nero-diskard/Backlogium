package com.example.backlogium.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every path through the admission rule, including each rejection — the rejections are the point.
 * Admitting the wrong thing is worse than admitting nothing: an owned game admitted as borrowed
 * would be tracked by the wrong session mechanism entirely, and a screensaver admitted as a game
 * erodes trust in the whole feature.
 */
class SharedGameAdmissionPolicyTest {

    private val policy = SharedGameAdmissionPolicy()

    private fun facts(
        isTracked: Boolean = false,
        isExcluded: Boolean = false,
        firstObservedAt: Long = 1_000L,
        lastSuccessfulSyncAt: Long = 2_000L,
        store: StoreVerification? = null,
    ) = AdmissionFacts(
        appId = 440L,
        isTracked = isTracked,
        isExcluded = isExcluded,
        firstObservedAt = firstObservedAt,
        lastSuccessfulSyncAt = lastSuccessfulSyncAt,
        store = store,
    )

    @Test
    fun allConditionsMet_admits() {
        assertEquals(
            AdmissionDecision.Admit,
            policy.evaluate(facts(store = StoreVerification.GAME)),
        )
    }

    @Test
    fun localChecksPass_asksTheStoreBeforeDeciding() {
        // The one request in this rule happens only after the free checks have passed.
        assertEquals(AdmissionDecision.NeedsStoreVerification, policy.evaluate(facts()))
    }

    @Test
    fun alreadyTracked_isRejectedBeforeAnythingElse() {
        assertEquals(
            AdmissionDecision.AlreadyTracked,
            policy.evaluate(facts(isTracked = true, store = StoreVerification.GAME)),
        )
    }

    @Test
    fun removedByThePlayer_staysRemoved() {
        // Sticky by design: a removal that undoes itself is worse than no removal at all.
        assertEquals(
            AdmissionDecision.Excluded,
            policy.evaluate(facts(isExcluded = true, store = StoreVerification.GAME)),
        )
    }

    @Test
    fun noSyncSinceFirstObservation_waitsRatherThanGuessing() {
        assertEquals(
            AdmissionDecision.AwaitingSync,
            policy.evaluate(
                facts(
                    firstObservedAt = 2_000L,
                    lastSuccessfulSyncAt = 1_000L,
                    store = StoreVerification.GAME,
                ),
            ),
        )
    }

    @Test
    fun syncExactlyAtFirstObservation_stillWaits() {
        // Strictly after, because a sync concurrent with the first observation cannot be said to
        // have seen a library the game was already missing from.
        assertEquals(
            AdmissionDecision.AwaitingSync,
            policy.evaluate(facts(firstObservedAt = 2_000L, lastSuccessfulSyncAt = 2_000L)),
        )
    }

    @Test
    fun noSyncHasEverCompleted_waits() {
        // A library the app has never seen cannot establish that anything is missing from it.
        assertEquals(
            AdmissionDecision.AwaitingSync,
            policy.evaluate(facts(firstObservedAt = 0L, lastSuccessfulSyncAt = 0L)),
        )
    }

    @Test
    fun storeSaysNotAGame_isRejectedPermanently() {
        assertEquals(
            AdmissionDecision.NotAGame,
            policy.evaluate(facts(store = StoreVerification.NOT_A_GAME)),
        )
    }

    @Test
    fun storeUnreachable_defersRatherThanAdmitting() {
        // Distinct from NotAGame: nothing is admitted on incomplete information, and the id is
        // reconsidered the next time it is observed.
        assertEquals(
            AdmissionDecision.VerificationUnavailable,
            policy.evaluate(facts(store = StoreVerification.UNAVAILABLE)),
        )
    }

    @Test
    fun rejectionOrder_prefersTheCheapestAnswer() {
        // A tracked *and* excluded *and* unsynced app id reports being tracked, so the caller
        // stops at the answer that requires no further work.
        assertEquals(
            AdmissionDecision.AlreadyTracked,
            policy.evaluate(
                facts(
                    isTracked = true,
                    isExcluded = true,
                    firstObservedAt = 5_000L,
                    lastSuccessfulSyncAt = 0L,
                ),
            ),
        )
    }
}
