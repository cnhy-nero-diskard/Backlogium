package com.example.backlogium.domain

import javax.inject.Inject

/** What Steam's store was able to say about an unrecognised app id. */
enum class StoreVerification {
    GAME,
    NOT_A_GAME,

    /** No usable answer — the store could not be reached, or would not describe the id. */
    UNAVAILABLE,
}

/** The facts admission is decided from. All local except [store], which costs one request. */
data class AdmissionFacts(
    val appId: Long,
    /** A `games` row already exists for this app id, whatever its source. */
    val isTracked: Boolean,
    /** The player removed this app id after it had been admitted. */
    val isExcluded: Boolean,
    /** When this app id was first observed in presence while unrecognised. */
    val firstObservedAt: Long,
    /** When the last successful owned-games sync completed; 0 when none ever has. */
    val lastSuccessfulSyncAt: Long,
    /** Null until the store has been asked — see [AdmissionDecision.NeedsStoreVerification]. */
    val store: StoreVerification? = null,
)

/**
 * Why an observation did or did not become a tracked family-shared game. Every non-[Admit] outcome
 * is named rather than collapsed into a boolean, because they differ in what should happen next:
 * some are permanent, some are simply "not yet".
 */
sealed interface AdmissionDecision {
    /** Admit as family-shared. */
    data object Admit : AdmissionDecision

    /** The cheap local checks passed; the store has not been asked yet. */
    data object NeedsStoreVerification : AdmissionDecision

    /** A `games` row already exists — including one admitted moments ago. */
    data object AlreadyTracked : AdmissionDecision

    /** Removed by the player. Sticky: a removal that undoes itself is worse than no removal. */
    data object Excluded : AdmissionDecision

    /**
     * No successful sync has completed since this app id was first observed, so the app cannot yet
     * tell a borrowed game from an owned one it simply has not synced.
     */
    data object AwaitingSync : AdmissionDecision

    /** The store answered, and this app id is not a game. Permanent. */
    data object NotAGame : AdmissionDecision

    /** The store could not be reached. Reconsidered on a later observation. */
    data object VerificationUnavailable : AdmissionDecision
}

/**
 * The admission rule, as a pure function of already-gathered facts (add-family-shared-games).
 *
 * Deliberately ordered cheapest-first, and deliberately pure: the store request — the only part
 * that costs anything — happens between the two calls the caller makes, and every rejection path
 * is testable as a table with no network, no Room, and no clock.
 *
 * The rule is three conditions, all of which must hold:
 *
 * 1. Presence reports an app id with no `games` row and no exclusion.
 * 2. A successful sync has completed *since the app id was first observed*. This is the condition
 *    that prevents the most likely false positive — a game the player owns but the app has not
 *    synced yet would otherwise be admitted as borrowed, and would then be tracked by the wrong
 *    session mechanism entirely.
 * 3. Steam's store confirms the app id is a game. Family Sharing covers a whole library, tools and
 *    applications included, and admitting a screensaver as a tracked game is the kind of thing
 *    that erodes trust in the whole feature.
 *
 * Nothing is admitted on incomplete information: an id that cannot be verified is deferred, not
 * guessed at, and reconsidered the next time it is observed.
 */
class SharedGameAdmissionPolicy @Inject constructor() {

    fun evaluate(facts: AdmissionFacts): AdmissionDecision = when {
        facts.isTracked -> AdmissionDecision.AlreadyTracked
        facts.isExcluded -> AdmissionDecision.Excluded
        // Strictly after: a sync that completed *before* the first observation says nothing about
        // an app id the player may have bought since. Zero means no sync has ever completed, and
        // a library the app has never seen cannot establish that anything is missing from it.
        facts.lastSuccessfulSyncAt <= facts.firstObservedAt -> AdmissionDecision.AwaitingSync
        facts.store == null -> AdmissionDecision.NeedsStoreVerification
        facts.store == StoreVerification.NOT_A_GAME -> AdmissionDecision.NotAGame
        facts.store == StoreVerification.UNAVAILABLE -> AdmissionDecision.VerificationUnavailable
        else -> AdmissionDecision.Admit
    }
}
