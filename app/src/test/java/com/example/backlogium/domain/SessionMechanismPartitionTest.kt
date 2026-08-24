package com.example.backlogium.domain

import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.domain.PresenceSessionDeriver.Observation
import com.example.backlogium.domain.SessionDiffer.GameDiffState
import com.example.backlogium.domain.SessionDiffer.PollGame
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CLAUDE.md`: "Two independent session detectors would produce records with disagreeing boundaries
 * that cannot be deduplicated — this is a load-bearing constraint, not a stylistic preference."
 *
 * This change adds a second mechanism, so the constraint is preserved by partitioning: playtime
 * diffing for owned games, presence derivation for shared ones, and never both for one game. The
 * partition lives in the *wiring* — `GameDao.ownedGamesForDiffing` on one side, a stored source of
 * `FAMILY_SHARED` on the other — so this test exercises that wiring rather than either mechanism.
 */
class SessionMechanismPartitionTest {

    private val differ = SessionDiffer()
    private val deriver = PresenceSessionDeriver()

    private val owned = Game(
        appId = 440L,
        name = "Owned",
        iconUrl = "",
        playtimeForever = 100,
        playtime2Weeks = 0,
        lastPlaytime = 90,
        source = GameSource.STEAM_OWNED,
    )
    private val shared = Game(
        appId = 620L,
        name = "Shared",
        iconUrl = "",
        playtimeForever = 0,
        playtime2Weeks = 0,
        lastPlaytime = 0,
        source = GameSource.FAMILY_SHARED,
    )

    @Test
    fun oneCycle_givesEachGameActionsFromExactlyOneMechanism() = runTest {
        val dao = FakeGameDao(listOf(owned, shared))

        // The diffing side is fed by the query, not by every row: a shared game cannot reach it.
        val diffable = dao.ownedGamesForDiffing()
        val diff = differ.diff(
            polls = diffable.map { PollGame(it.appId, it.playtimeForever) },
            priorStates = diffable.associate { it.appId to GameDiffState(it.lastPlaytime) },
            now = 2_000L,
            previousPollAt = 1_000L,
        )

        // The derivation side only ever sees an app id whose stored source is FAMILY_SHARED.
        val observedAppId = shared.appId.takeIf {
            dao.getById(it)?.source == GameSource.FAMILY_SHARED
        }
        val derived = deriver.derive(Observation(observedAppId, 2_000L), openSession = null)

        val diffedAppIds = diff.actions.map { it.appId }.toSet()
        val derivedAppIds = derived.actions.map { it.appId }.toSet()

        assertEquals(setOf(owned.appId), diffedAppIds)
        assertEquals(setOf(shared.appId), derivedAppIds)
        assertTrue(
            "No game may receive session actions from both mechanisms in one cycle",
            diffedAppIds.intersect(derivedAppIds).isEmpty(),
        )
    }

    @Test
    fun anOwnedGameObservedInPresence_producesNoDerivedSession() = runTest {
        val dao = FakeGameDao(listOf(owned, shared))

        // What the wiring does with an owned game in presence: hands the deriver null, exactly as
        // "not in a game" does. Deriving for it as well would be strictly worse than what exists —
        // coarser boundaries, gaps whenever the app is closed — on top of an unreconcilable overlap.
        val observedAppId = owned.appId.takeIf {
            dao.getById(it)?.source == GameSource.FAMILY_SHARED
        }
        val derived = deriver.derive(Observation(observedAppId, 2_000L), openSession = null)

        assertTrue(derived.actions.isEmpty())
    }

    @Test
    fun aSharedGameIsNeverDiffed_evenWhenItHasStoredPlaytime() = runTest {
        // Defence in depth: even a shared row carrying non-zero playtime (a converted game whose
        // source write was somehow lost) is excluded by the query itself.
        val dao = FakeGameDao(listOf(shared.copy(playtimeForever = 500, lastPlaytime = 100)))

        val diffable = dao.ownedGamesForDiffing()

        assertTrue(diffable.isEmpty())
    }
}
