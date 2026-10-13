package com.example.backlogium.domain

import com.example.backlogium.domain.SessionDiffer.GameDiffState
import com.example.backlogium.domain.SessionDiffer.OpenSession
import com.example.backlogium.domain.SessionDiffer.PollGame
import com.example.backlogium.domain.SessionDiffer.SessionAction
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-planner fixtures for `recover-missed-playtime` tasks 3.1-3.6, including regression tests for
 * the parent review: start-date-atomic timed sessions, gap subtraction, checked arithmetic and
 * identity rules, injected evaluation date, target-in-window validation, bounded recipes, cloud
 * fence/estimate rejection and deterministic ties. No Robolectric, no Room, no network.
 */
class PlaytimeAttributionPlanTest {

    private val utc = ZoneId.of("UTC")
    private val newYork = ZoneId.of("America/New_York")
    private val losAngeles = ZoneId.of("America/Los_Angeles")

    // ---- 3.1 one plan, high-water authority stays with SessionDiffer ----

    @Test
    fun `differ remains the quantity authority and the planner places its delta`() {
        val differ = SessionDiffer()
        val diff = differ.diff(
            polls = listOf(PollGame(1L, 570)),
            priorStates = mapOf(1L to GameDiffState(lastPlaytime = 500)),
            now = ts("2024-01-10T12:00:00"),
            previousPollAt = ts("2024-01-09T12:00:00"),
        )
        assertEquals(70, diff.playedDeltaByAppId.getValue(1L))

        val plan = plan(
            authorizedDeltaMinutes = diff.playedDeltaByAppId.getValue(1L),
            previousObservationAt = ts("2024-01-09T12:00:00"),
            recoveryObservedAt = ts("2024-01-10T12:00:00"),
        )

        assertEquals(70, plan.authorizedMinutes)
        assertEquals(70, plan.creditedMinutes)
        assertEquals(listOf(date("2024-01-10") to 70), plan.residualAllocations().map { it.date to it.minutes })
        assertTrue(plan.sessionActions.isEmpty())
    }

    @Test
    fun `contribution identity is derived from the cumulative range`() {
        val plan = plan(
            authorizedDeltaMinutes = 70,
            previousObservationAt = ts("2024-01-09T00:00:00"),
            recoveryObservedAt = ts("2024-01-10T00:00:00"),
            priorHighWaterMinutes = 500,
        )

        val id = plan.contributionId!!
        assertEquals(500, id.quantityRange.fromMinutes)
        assertEquals(570, id.quantityRange.toMinutes)
        assertEquals(70, id.authorizedMinutes)
        assertEquals("steam:1|1|7|500|570", id.canonical())
    }

    @Test
    fun `zero delta produces no activity and no rows`() {
        val plan = plan(authorizedDeltaMinutes = 0, previousObservationAt = ts("2024-01-09T00:00:00"))

        assertEquals(0, plan.creditedMinutes)
        assertTrue(plan.residualAllocations().isEmpty())
        assertNull(plan.pending)
    }

    // ---- 3.2 short-gap usability, fallback, stale-open, targeted timing ----

    @Test
    fun `same-date short gap keeps a timestamped session`() {
        val previous = ts("2024-01-08T10:00:00")
        val recovery = ts("2024-01-08T10:20:00")
        val plan = plan(authorizedDeltaMinutes = 20, previousObservationAt = previous, recoveryObservedAt = recovery)

        val open = plan.sessionActions.single() as SessionAction.Open
        assertEquals(previous, open.startAt)
        assertEquals(recovery, open.endAt)
        assertEquals(20, open.minutes)
        assertEquals(20, plan.sessionMinutes)
        assertTrue(plan.residualAllocations().isEmpty())
    }

    @Test
    fun `thirty minute boundary still keeps a session`() {
        val plan = plan(
            authorizedDeltaMinutes = 30,
            previousObservationAt = ts("2024-01-08T10:00:00"),
            recoveryObservedAt = ts("2024-01-08T10:30:00"),
        )
        assertEquals(30, plan.sessionMinutes)
        assertTrue(plan.residualAllocations().isEmpty())
    }

    @Test
    fun `beyond thirty minutes falls back to the recovery date`() {
        val plan = plan(
            authorizedDeltaMinutes = 30,
            previousObservationAt = ts("2024-01-08T10:00:00"),
            recoveryObservedAt = ts("2024-01-08T10:31:00"),
        )
        assertEquals(0, plan.sessionMinutes)
        assertEquals(
            listOf(date("2024-01-08") to 30),
            plan.residualAllocations().map { it.date to it.minutes },
        )
    }

    @Test
    fun `delta above the elapsed-minute ceiling plus one uses fallback`() {
        val plan = plan(
            authorizedDeltaMinutes = 10,
            previousObservationAt = ts("2024-01-08T10:00:00"),
            recoveryObservedAt = ts("2024-01-08T10:05:00"),
        )
        assertEquals(0, plan.sessionMinutes)
        assertEquals(10, plan.residualAllocations().single().minutes)
    }

    @Test
    fun `just within the rounding guard keeps a session`() {
        val plan = plan(
            authorizedDeltaMinutes = 6,
            previousObservationAt = ts("2024-01-08T10:00:00"),
            recoveryObservedAt = ts("2024-01-08T10:05:00"),
        )
        assertEquals(6, plan.sessionMinutes)
    }

    @Test
    fun `crossing midnight without continuity evidence falls back and credits the recovery date`() {
        val plan = plan(
            authorizedDeltaMinutes = 20,
            previousObservationAt = ts("2024-01-08T23:50:00"),
            recoveryObservedAt = ts("2024-01-09T00:10:00"),
        )
        assertEquals(0, plan.sessionMinutes)
        assertEquals(
            listOf(date("2024-01-09") to 20),
            plan.residualAllocations().map { it.date to it.minutes },
        )
    }

    @Test
    fun `crossing midnight with validated continuity keeps a session`() {
        val previous = ts("2024-01-08T23:50:00")
        val recovery = ts("2024-01-09T00:10:00")
        val plan = plan(
            authorizedDeltaMinutes = 20,
            previousObservationAt = previous,
            recoveryObservedAt = recovery,
            evidence = listOf(evidence("c", previous, recovery)),
        )
        val open = plan.sessionActions.single() as SessionAction.Open
        assertEquals(previous, open.startAt)
        assertEquals(recovery, open.endAt)
        assertEquals(20, plan.sessionMinutes)
    }

    @Test
    fun `a gapped interval cannot establish midnight continuity`() {
        val previous = ts("2024-01-08T23:50:00")
        val recovery = ts("2024-01-09T00:10:00")
        val plan = plan(
            authorizedDeltaMinutes = 20,
            previousObservationAt = previous,
            recoveryObservedAt = recovery,
            gapToleranceMillis = 10L * 60 * 1000,
            evidence = listOf(
                evidence(
                    "g",
                    previous,
                    recovery,
                    gapFromAt = ts("2024-01-09T00:00:00"),
                    gapRecoveredAt = ts("2024-01-09T00:05:00"),
                ),
            ),
        )
        assertEquals(0, plan.sessionMinutes)
        assertEquals(
            listOf(date("2024-01-09") to 5),
            plan.residualAllocations().map { it.date to it.minutes },
        )
        assertEquals(15, plan.timedMinutes)
        assertEquals(20, plan.creditedMinutes)
        assertEquals(2, plan.timedPlacements.size)
        assertTrue(plan.timedPlacements.none {
            it.startAt < ts("2024-01-09T00:05:00") && it.endAt > ts("2024-01-09T00:00:00")
        })
    }

    @Test
    fun `no-evidence Wednesday recovers exactly seventy minutes`() {
        val plan = plan(
            authorizedDeltaMinutes = 70,
            previousObservationAt = ts("2024-01-09T12:00:00"),
            recoveryObservedAt = ts("2024-01-10T12:00:00"),
        )
        assertEquals(
            listOf(date("2024-01-10") to 70),
            plan.residualAllocations().map { it.date to it.minutes },
        )
        assertEquals(PlaytimeAllocationMethod.SINGLE_DAY_ESTIMATE, plan.residual!!.method)
        assertTrue(plan.changedDates().none { it == date("2024-01-09") })
    }

    @Test
    fun `stale open session is closed at its last trustworthy boundary and absorbs nothing`() {
        val plan = plan(
            authorizedDeltaMinutes = 105,
            previousObservationAt = ts("2024-01-08T12:00:00"),
            recoveryObservedAt = ts("2024-01-12T12:00:00"),
            priorOpenSession = OpenSession(
                startAt = ts("2024-01-08T08:00:00"),
                minutes = 30,
                lastIncreaseAt = ts("2024-01-08T09:00:00"),
            ),
        )
        val close = plan.sessionActions.single() as SessionAction.Close
        assertEquals(ts("2024-01-08T08:00:00"), close.startAt)
        assertEquals(ts("2024-01-08T09:00:00"), close.endAt)
        assertEquals(0, plan.sessionMinutes)
        assertEquals(105, plan.residualAllocations().sumOf { it.minutes })
    }

    @Test
    fun `targeted end-only short gap without timing evidence stays date-known`() {
        val known = ts("2024-01-08T10:04:00")
        val plan = plan(
            authorizedDeltaMinutes = 4,
            previousObservationAt = ts("2024-01-08T10:00:00"),
            recoveryObservedAt = ts("2024-01-08T10:05:00"),
            knownPlayInstantAt = known,
        )
        // No invented start: the end-only target contributes date-known/clock-unknown, not a session.
        assertTrue(plan.sessionActions.none { it.addedMinutes > 0 })
        val allocation = plan.residualAllocations().single()
        assertEquals(date("2024-01-08"), allocation.date)
        assertEquals(4, allocation.minutes)
        assertEquals(PlaytimeAllocationMethod.KNOWN_TARGETED_DATE, allocation.method)
    }

    @Test
    fun `targeted end-only instant with continuity evidence keeps a session ending at the known instant`() {
        val previous = ts("2024-01-08T10:00:00")
        val known = ts("2024-01-08T10:04:00")
        val plan = plan(
            authorizedDeltaMinutes = 4,
            previousObservationAt = previous,
            recoveryObservedAt = ts("2024-01-08T10:05:00"),
            knownPlayInstantAt = known,
            evidence = listOf(evidence("c", previous, known)),
        )
        val open = plan.sessionActions.single() as SessionAction.Open
        assertEquals(previous, open.startAt)
        assertEquals(known, open.endAt)
        assertEquals(4, open.minutes)
    }

    @Test
    fun `targeted known instant establishes a date without inventing a start`() {
        val known = ts("2024-01-10T15:00:00")
        val plan = plan(
            authorizedDeltaMinutes = 50,
            previousObservationAt = ts("2024-01-08T12:00:00"),
            recoveryObservedAt = ts("2024-01-12T12:00:00"),
            knownPlayInstantAt = known,
        )
        assertTrue(plan.sessionActions.none { it.addedMinutes > 0 })
        val allocation = plan.residualAllocations().single()
        assertEquals(date("2024-01-10"), allocation.date)
        assertEquals(50, allocation.minutes)
        assertEquals(PlaytimeAllocationMethod.KNOWN_TARGETED_DATE, allocation.method)
    }

    // ---- 3.3 frozen recovery-zone calendar and earliest-remainder residual ----

    @Test
    fun `four-day exact distribution is 27 26 26 26 and stays retroactive`() {
        val plan = plan(
            authorizedDeltaMinutes = 105,
            previousObservationAt = ts("2024-01-08T12:00:00"),
            recoveryObservedAt = ts("2024-01-12T12:00:00"),
        )
        assertEquals(
            listOf(
                date("2024-01-09") to 27,
                date("2024-01-10") to 26,
                date("2024-01-11") to 26,
                date("2024-01-12") to 26,
            ),
            plan.residualAllocations().map { it.date to it.minutes },
        )
        assertTrue(plan.residualAllocations().all { it.method == PlaytimeAllocationMethod.MULTI_DAY_ESTIMATE })
        assertEquals(PlaytimePlacementProvenance.RETROACTIVE_PLAY, plan.provenance)
    }

    @Test
    fun `remainder smaller than the window leaves later dates without zero rows`() {
        val plan = plan(
            authorizedDeltaMinutes = 2,
            previousObservationAt = ts("2024-01-08T12:00:00"),
            recoveryObservedAt = ts("2024-01-12T12:00:00"),
        )
        val allocations = plan.residualAllocations()
        assertEquals(listOf(date("2024-01-09") to 1, date("2024-01-10") to 1), allocations.map { it.date to it.minutes })
        assertTrue(allocations.all { it.minutes > 0 })
    }

    @Test
    fun `leap day is a normal eligible date`() {
        val plan = plan(
            authorizedDeltaMinutes = 5,
            previousObservationAt = ts("2024-02-28T12:00:00"),
            recoveryObservedAt = ts("2024-03-01T12:00:00"),
        )
        assertEquals(
            listOf(date("2024-02-29") to 3, date("2024-03-01") to 2),
            plan.residualAllocations().map { it.date to it.minutes },
        )
    }

    @Test
    fun `spring-forward DST day keeps its date keys`() {
        val plan = plan(
            authorizedDeltaMinutes = 3,
            previousObservationAt = ts("2024-03-09T12:00:00", newYork),
            recoveryObservedAt = ts("2024-03-11T12:00:00", newYork),
            zone = newYork,
        )
        assertEquals(
            listOf(date("2024-03-10") to 2, date("2024-03-11") to 1),
            plan.residualAllocations().map { it.date to it.minutes },
        )
    }

    @Test
    fun `date keys come from the recovery zone and are not re-bucketed later`() {
        val previous = ts("2024-01-09T03:00:00")
        val recovery = ts("2024-01-09T04:00:00")
        val plan = plan(
            authorizedDeltaMinutes = 15,
            previousObservationAt = previous,
            recoveryObservedAt = recovery,
            zone = losAngeles,
            previousZone = utc,
        )
        assertEquals(listOf(date("2024-01-08") to 15), plan.residualAllocations().map { it.date to it.minutes })
        assertEquals(losAngeles, plan.zone)

        val other = plan(
            authorizedDeltaMinutes = 15,
            previousObservationAt = previous,
            recoveryObservedAt = recovery,
            zone = utc,
        )
        assertEquals(listOf(date("2024-01-09") to 15), other.residualAllocations().map { it.date to it.minutes })
        assertEquals(listOf(date("2024-01-08") to 15), plan.residualAllocations().map { it.date to it.minutes })
    }

    // ---- 3.4 evidence union, consumed subtraction, bounded rounding ----

    @Test
    fun `thirty-five evidence plus seventy residual conserves one hundred and five`() {
        val plan = plan(
            authorizedDeltaMinutes = 105,
            previousObservationAt = ts("2024-01-08T12:00:00"),
            recoveryObservedAt = ts("2024-01-12T12:00:00"),
            evidence = listOf(evidence("e1", ts("2024-01-10T09:00:00"), ts("2024-01-10T09:35:00"))),
        )
        assertEquals(35, plan.timedMinutes)
        assertEquals(70, plan.residualMinutes)
        assertEquals(105, plan.creditedMinutes)
        val timed = plan.timedPlacements.single()
        assertEquals(35, timed.minutes)
        assertEquals(date("2024-01-10"), timed.date)
    }

    @Test
    fun `same-game overlapping local and cloud spans are split by actual coverage`() {
        val plan = plan(
            authorizedDeltaMinutes = 105,
            previousObservationAt = ts("2024-01-08T00:00:00"),
            recoveryObservedAt = ts("2024-01-08T02:00:00"),
            activeReaderIdentity = CloudReaderIdentity("steam:1", 1),
            evidence = listOf(
                evidence("local", ts("2024-01-08T00:00:00"), ts("2024-01-08T01:00:00"), source = PlaytimeEvidenceSource.LOCAL),
                evidence(
                    "cloud",
                    ts("2024-01-08T00:15:00"),
                    ts("2024-01-08T01:30:00"),
                    source = PlaytimeEvidenceSource.CLOUD,
                    readerIdentity = CloudReaderIdentity("steam:1", 1),
                ),
            ),
        )
        assertEquals(90, plan.timedMinutes)
        assertEquals(15, plan.residualMinutes)
        assertEquals(105, plan.creditedMinutes)

        // The local-only and shared portions are LOCAL; the 01:00-01:30 tail stays CLOUD-only.
        val cloudOnly = plan.timedPlacements.single { it.source == PlaytimeEvidenceSource.CLOUD }
        assertEquals(30, cloudOnly.minutes)
        assertEquals(listOf("cloud"), cloudOnly.evidenceIds)
        val shared = plan.timedPlacements.first { it.evidenceIds == listOf("cloud", "local") }
        assertEquals(45, shared.minutes)
        assertEquals(PlaytimeEvidenceSource.LOCAL, shared.source)
    }

    @Test
    fun `consumed spans are subtracted before placement`() {
        val plan = plan(
            authorizedDeltaMinutes = 200,
            previousObservationAt = ts("2024-01-08T00:00:00"),
            recoveryObservedAt = ts("2024-01-08T02:00:00"),
            evidence = listOf(evidence("e", ts("2024-01-08T00:00:00"), ts("2024-01-08T02:00:00"))),
            consumedSpans = listOf(
                ConsumedPlaytimeSpan(ts("2024-01-08T00:30:00"), ts("2024-01-08T01:00:00")),
                ConsumedPlaytimeSpan(ts("2024-01-08T00:45:00"), ts("2024-01-08T01:15:00")),
            ),
        )
        assertEquals(75, plan.timedMinutes)
        assertEquals(125, plan.residualMinutes)
        assertEquals(200, plan.creditedMinutes)
    }

    @Test
    fun `fractional-minute spans combine before largest-remainder rounding`() {
        val plan = plan(
            authorizedDeltaMinutes = 3,
            previousObservationAt = ts("2024-01-08T00:00:00"),
            recoveryObservedAt = ts("2024-01-08T01:00:00"),
            evidence = listOf(
                evidence("a", ts("2024-01-08T00:00:00"), ts("2024-01-08T00:01:30")),
                evidence("b", ts("2024-01-08T00:02:00"), ts("2024-01-08T00:03:30")),
            ),
        )
        assertEquals(listOf(2, 1), plan.timedPlacements.map { it.minutes })
        assertEquals(3, plan.creditedMinutes)
    }

    @Test
    fun `equal spans split with the earlier interval winning the remainder`() {
        val plan = plan(
            authorizedDeltaMinutes = 10,
            previousObservationAt = ts("2024-01-08T00:00:00"),
            recoveryObservedAt = ts("2024-01-08T12:00:00"),
            evidence = listOf(
                evidence("a", ts("2024-01-08T00:00:00"), ts("2024-01-08T04:00:00")),
                evidence("b", ts("2024-01-08T04:00:00"), ts("2024-01-08T08:00:00")),
            ),
        )
        assertEquals(listOf(5, 5), plan.timedPlacements.map { it.minutes })
    }

    @Test
    fun `equivalent spans tie-break deterministically by evidence id`() {
        val forward = plan(
            authorizedDeltaMinutes = 5,
            previousObservationAt = ts("2024-01-08T00:00:00"),
            recoveryObservedAt = ts("2024-01-08T12:00:00"),
            evidence = listOf(
                evidence("a", ts("2024-01-08T00:00:00"), ts("2024-01-08T04:00:00")),
                evidence("b", ts("2024-01-08T04:00:00"), ts("2024-01-08T08:00:00")),
            ),
        )
        val reversed = plan(
            authorizedDeltaMinutes = 5,
            previousObservationAt = ts("2024-01-08T00:00:00"),
            recoveryObservedAt = ts("2024-01-08T12:00:00"),
            evidence = listOf(
                evidence("b", ts("2024-01-08T04:00:00"), ts("2024-01-08T08:00:00")),
                evidence("a", ts("2024-01-08T00:00:00"), ts("2024-01-08T04:00:00")),
            ),
        )
        assertEquals(listOf(3, 2), forward.timedPlacements.map { it.minutes })
        assertEquals(listOf(3, 2), reversed.timedPlacements.map { it.minutes })
    }

    @Test
    fun `wrong application identity evidence is rejected`() {
        val plan = plan(
            authorizedDeltaMinutes = 20,
            previousObservationAt = ts("2024-01-08T00:00:00"),
            recoveryObservedAt = ts("2024-01-08T01:00:00"),
            evidence = listOf(evidence("x", ts("2024-01-08T00:00:00"), ts("2024-01-08T01:00:00"), appId = 999L)),
        )
        assertEquals(0, plan.timedMinutes)
        assertTrue(plan.diagnostics.any { it.code == PlaytimeDiagnosticCode.WRONG_APP })
        assertEquals(20, plan.creditedMinutes)
    }

    @Test
    fun `wrong account evidence is rejected`() {
        val plan = plan(
            authorizedDeltaMinutes = 20,
            previousObservationAt = ts("2024-01-08T00:00:00"),
            recoveryObservedAt = ts("2024-01-08T01:00:00"),
            evidence = listOf(
                PlaytimeTimingEvidence(
                    evidenceId = "other",
                    appId = 1L,
                    account = "steam:2",
                    startAt = ts("2024-01-08T00:00:00"),
                    endAt = ts("2024-01-08T01:00:00"),
                ),
            ),
        )
        assertEquals(0, plan.timedMinutes)
        assertTrue(plan.diagnostics.any { it.code == PlaytimeDiagnosticCode.WRONG_ACCOUNT })
        assertEquals(20, plan.creditedMinutes)
    }

    @Test
    fun `local evidence without an explicit account is rejected`() {
        val plan = plan(
            authorizedDeltaMinutes = 20,
            previousObservationAt = ts("2024-01-08T00:00:00"),
            recoveryObservedAt = ts("2024-01-08T01:00:00"),
            evidence = listOf(
                PlaytimeTimingEvidence(
                    evidenceId = "anon",
                    appId = 1L,
                    startAt = ts("2024-01-08T00:00:00"),
                    endAt = ts("2024-01-08T01:00:00"),
                ),
            ),
        )
        assertEquals(0, plan.timedMinutes)
        assertEquals(1, plan.rejectedEvidenceCount)
        assertTrue(plan.diagnostics.any { it.code == PlaytimeDiagnosticCode.WRONG_ACCOUNT })
        assertEquals(20, plan.creditedMinutes)
    }

    @Test
    fun `stale cloud generation is rejected`() {
        val plan = plan(
            authorizedDeltaMinutes = 20,
            previousObservationAt = ts("2024-01-08T00:00:00"),
            recoveryObservedAt = ts("2024-01-08T01:00:00"),
            activeReaderIdentity = CloudReaderIdentity("steam:1", 2),
            evidence = listOf(
                cloudEvidence("cloud", 1),
            ),
        )
        assertEquals(0, plan.timedMinutes)
        assertEquals(1, plan.rejectedEvidenceCount)
        assertEquals(20, plan.creditedMinutes)
        assertTrue(plan.diagnostics.any { it.code == PlaytimeDiagnosticCode.STALE_GENERATION })
    }

    @Test
    fun `cloud evidence without an active reader fence is rejected`() {
        val plan = plan(
            authorizedDeltaMinutes = 20,
            previousObservationAt = ts("2024-01-08T00:00:00"),
            recoveryObservedAt = ts("2024-01-08T01:00:00"),
            evidence = listOf(cloudEvidence("cloud", 1)),
        )
        assertEquals(0, plan.timedMinutes)
        assertEquals(1, plan.rejectedEvidenceCount)
        assertTrue(plan.diagnostics.any { it.code == PlaytimeDiagnosticCode.CLOUD_WITHOUT_ACTIVE_IDENTITY })
        assertEquals(20, plan.creditedMinutes)
    }

    @Test
    fun `estimates never count as validated timing evidence`() {
        val plan = plan(
            authorizedDeltaMinutes = 20,
            previousObservationAt = ts("2024-01-08T00:00:00"),
            recoveryObservedAt = ts("2024-01-08T01:00:00"),
            evidence = listOf(evidence("est", ts("2024-01-08T00:00:00"), ts("2024-01-08T01:00:00"), source = PlaytimeEvidenceSource.ESTIMATE)),
        )
        assertEquals(0, plan.timedMinutes)
        assertEquals(1, plan.rejectedEvidenceCount)
        assertTrue(plan.diagnostics.any { it.code == PlaytimeDiagnosticCode.ESTIMATE_NOT_VALID })
        assertEquals(20, plan.creditedMinutes)
    }

    @Test
    fun `gap above tolerance rejects the whole interval`() {
        val plan = plan(
            authorizedDeltaMinutes = 100,
            previousObservationAt = ts("2024-01-08T00:00:00"),
            recoveryObservedAt = ts("2024-01-08T01:00:00"),
            gapToleranceMillis = 10L * 60 * 1000,
            evidence = listOf(
                evidence(
                    "g",
                    ts("2024-01-08T00:00:00"),
                    ts("2024-01-08T01:00:00"),
                    gapFromAt = ts("2024-01-08T00:20:00"),
                    gapRecoveredAt = ts("2024-01-08T00:50:00"),
                ),
            ),
        )
        assertEquals(0, plan.timedMinutes)
        assertTrue(plan.diagnostics.any { it.code == PlaytimeDiagnosticCode.GAP_ABOVE_TOLERANCE })
        assertEquals(100, plan.creditedMinutes)
    }

    @Test
    fun `sub-tolerance gap subtracts the uncovered portion instead of being included whole`() {
        val plan = plan(
            authorizedDeltaMinutes = 100,
            previousObservationAt = ts("2024-01-08T00:00:00"),
            recoveryObservedAt = ts("2024-01-08T01:00:00"),
            gapToleranceMillis = 40L * 60 * 1000,
            evidence = listOf(
                evidence(
                    "g",
                    ts("2024-01-08T00:00:00"),
                    ts("2024-01-08T01:00:00"),
                    gapFromAt = ts("2024-01-08T00:20:00"),
                    gapRecoveredAt = ts("2024-01-08T00:50:00"),
                ),
            ),
        )
        // Usable = [00:00,00:20) + [00:50,01:00), the uncovered 30 minutes never count.
        assertEquals(30, plan.timedMinutes)
        assertEquals(70, plan.residualMinutes)
        assertEquals(100, plan.creditedMinutes)
    }

    @Test
    fun `a gap lying outside the interval never fabricates segments`() {
        val plan = plan(
            authorizedDeltaMinutes = 100,
            previousObservationAt = ts("2024-01-08T00:00:00"),
            recoveryObservedAt = ts("2024-01-08T02:00:00"),
            gapToleranceMillis = 40L * 60 * 1000,
            evidence = listOf(
                evidence(
                    "g",
                    ts("2024-01-08T00:10:00"),
                    ts("2024-01-08T01:00:00"),
                    gapFromAt = ts("2024-01-08T03:00:00"),
                    gapRecoveredAt = ts("2024-01-08T03:30:00"),
                ),
            ),
        )
        assertEquals(50, plan.timedMinutes)
        assertEquals(50, plan.residualMinutes)
        assertEquals(100, plan.creditedMinutes)
    }

    @Test
    fun `duplicate evidence identities are counted once`() {
        val plan = plan(
            authorizedDeltaMinutes = 100,
            previousObservationAt = ts("2024-01-08T00:00:00"),
            recoveryObservedAt = ts("2024-01-08T01:00:00"),
            evidence = listOf(
                evidence("dup", ts("2024-01-08T00:00:00"), ts("2024-01-08T01:00:00")),
                evidence("dup", ts("2024-01-08T00:00:00"), ts("2024-01-08T01:00:00")),
            ),
        )
        assertEquals(60, plan.timedMinutes)
        assertEquals(listOf("dup"), plan.timedPlacements.single().evidenceIds)
        assertEquals(100, plan.creditedMinutes)
    }

    @Test
    fun `a fully covered date is excluded from the residual`() {
        val day = date("2024-01-10")
        val dayStart = day.atStartOfDay(utc).toInstant().toEpochMilli()
        val dayEnd = day.plusDays(1).atStartOfDay(utc).toInstant().toEpochMilli()
        val plan = plan(
            authorizedDeltaMinutes = 3000,
            previousObservationAt = ts("2024-01-08T12:00:00"),
            recoveryObservedAt = ts("2024-01-12T12:00:00"),
            evidence = listOf(evidence("full", dayStart, dayEnd)),
        )
        assertEquals(1440, plan.timedMinutes)
        assertEquals(
            listOf(
                date("2024-01-09") to 520,
                date("2024-01-11") to 520,
                date("2024-01-12") to 520,
            ),
            plan.residualAllocations().map { it.date to it.minutes },
        )
        assertEquals(listOf(day..day), plan.residual!!.excludedRanges)
        assertEquals(3000, plan.creditedMinutes)
    }

    @Test
    fun `adjacent local and cloud fragments that jointly cover a day are excluded from the residual`() {
        val day = date("2024-01-10")
        val dayStart = day.atStartOfDay(utc).toInstant().toEpochMilli()
        val noon = day.atTime(12, 0).atZone(utc).toInstant().toEpochMilli()
        val dayEnd = day.plusDays(1).atStartOfDay(utc).toInstant().toEpochMilli()
        val plan = plan(
            authorizedDeltaMinutes = 3000,
            previousObservationAt = ts("2024-01-08T12:00:00"),
            recoveryObservedAt = ts("2024-01-12T12:00:00"),
            activeReaderIdentity = CloudReaderIdentity("steam:1", 1),
            evidence = listOf(
                evidence("localHalf", dayStart, noon, source = PlaytimeEvidenceSource.LOCAL),
                evidence(
                    "cloudHalf",
                    noon,
                    dayEnd,
                    source = PlaytimeEvidenceSource.CLOUD,
                    readerIdentity = CloudReaderIdentity("steam:1", 1),
                ),
            ),
        )
        assertEquals(1440, plan.timedMinutes)
        assertEquals(listOf(day..day), plan.residual!!.excludedRanges)
        assertEquals(
            listOf(
                date("2024-01-09") to 520,
                date("2024-01-11") to 520,
                date("2024-01-12") to 520,
            ),
            plan.residualAllocations().map { it.date to it.minutes },
        )
        assertEquals(3000, plan.creditedMinutes)
    }

    // ---- 3.5 pending inverted window and corrective replan ----

    @Test
    fun `inverted window retains quantity as pending date-unknown`() {
        val plan = plan(
            authorizedDeltaMinutes = 70,
            previousObservationAt = ts("2024-01-10T12:00:00"),
            recoveryObservedAt = ts("2024-01-09T12:00:00"),
        )
        assertNull(plan.residual)
        assertEquals(70, plan.pending!!.minutes)
        assertEquals(PlaytimePendingReason.INVERTED_WINDOW, plan.pending!!.reason)
        assertEquals(70, plan.creditedMinutes)
        assertEquals(PlaytimePlacementProvenance.RETROACTIVE_PLAY, plan.provenance)
    }

    @Test
    fun `no baseline window keeps authorized minutes pending`() {
        val plan = plan(
            authorizedDeltaMinutes = 25,
            previousObservationAt = null,
            recoveryObservedAt = ts("2024-01-08T12:00:00"),
        )
        assertNull(plan.residual)
        assertEquals(25, plan.pending!!.minutes)
        assertEquals(PlaytimePendingReason.NO_BASELINE_WINDOW, plan.pending!!.reason)
        assertEquals(25, plan.creditedMinutes)
    }

    @Test
    fun `zero-delta replan places retained pending quantity under its existing identity and is idempotent`() {
        val existing = contribution(500, 570)
        val placed = plan(
            authorizedDeltaMinutes = 0,
            unplacedPendingMinutes = 70,
            previousObservationAt = ts("2024-01-10T12:00:00"),
            recoveryObservedAt = ts("2024-01-12T12:00:00"),
            existingContributionId = existing,
        )
        assertEquals(existing, placed.contributionId)
        assertEquals(70, placed.creditedMinutes)
        assertNull(placed.pending)
        assertEquals(
            listOf(date("2024-01-11") to 35, date("2024-01-12") to 35),
            placed.residualAllocations().map { it.date to it.minutes },
        )
        assertEquals(PlaytimePlacementProvenance.RETROACTIVE_PLAY, placed.provenance)

        val replan = plan(
            authorizedDeltaMinutes = 0,
            unplacedPendingMinutes = 0,
            previousObservationAt = ts("2024-01-10T12:00:00"),
            recoveryObservedAt = ts("2024-01-12T12:00:00"),
        )
        assertEquals(0, replan.creditedMinutes)
        assertTrue(replan.residualAllocations().isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `retained pending cannot be combined with new delta in one request`() {
        // The caller loops separate requests; one graph may never carry both.
        plan(
            authorizedDeltaMinutes = 30,
            unplacedPendingMinutes = 70,
            previousObservationAt = ts("2024-01-10T12:00:00"),
            recoveryObservedAt = ts("2024-01-12T12:00:00"),
            priorHighWaterMinutes = 500,
            existingContributionId = contribution(500, 570),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `pending quantity without an existing identity is rejected instead of re-authorized`() {
        plan(
            authorizedDeltaMinutes = 0,
            unplacedPendingMinutes = 70,
            previousObservationAt = ts("2024-01-10T12:00:00"),
            recoveryObservedAt = ts("2024-01-12T12:00:00"),
            priorHighWaterMinutes = 500,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `existing contribution identity must match the request account`() {
        plan(
            authorizedDeltaMinutes = 0,
            unplacedPendingMinutes = 70,
            previousObservationAt = ts("2024-01-10T12:00:00"),
            recoveryObservedAt = ts("2024-01-12T12:00:00"),
            existingContributionId = PlaytimeContributionId(
                account = "steam:2",
                appId = 1L,
                baselineEpoch = 7L,
                quantityRange = PlaytimeQuantityRange(500, 570),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `existing contribution funded quantity must equal the placed total`() {
        plan(
            authorizedDeltaMinutes = 0,
            unplacedPendingMinutes = 70,
            previousObservationAt = ts("2024-01-10T12:00:00"),
            recoveryObservedAt = ts("2024-01-12T12:00:00"),
            existingContributionId = contribution(500, 560),
        )
    }

    @Test
    fun `pending placement never invents a day after the recovery date`() {
        val plan = plan(
            authorizedDeltaMinutes = 0,
            unplacedPendingMinutes = 40,
            previousObservationAt = ts("2024-01-09T12:00:00"),
            recoveryObservedAt = ts("2024-01-10T12:00:00"),
            existingContributionId = contribution(500, 540),
        )
        assertEquals(listOf(date("2024-01-10")), plan.residualAllocations().map { it.date })
    }

    // ---- 3.6 compact recipes and bounded paging over long windows ----

    @Test
    fun `ten-year gap preserves exact quantity with a compact recipe`() {
        val plan = plan(
            authorizedDeltaMinutes = 100_000,
            previousObservationAt = ts("2014-01-01T12:00:00"),
            recoveryObservedAt = ts("2024-01-01T12:00:00"),
        )
        val recipe = plan.residual!!
        assertEquals(100_000, recipe.totalMinutes)
        assertEquals(100_000, plan.creditedMinutes)
        assertTrue(recipe.windowDateCount > 3_600)
        assertEquals(100_000, plan.residualAllocations().sumOf { it.minutes })
    }

    @Test
    fun `one-minute ten-year gap yields exactly one positive row`() {
        val plan = plan(
            authorizedDeltaMinutes = 1,
            previousObservationAt = ts("2014-01-01T12:00:00"),
            recoveryObservedAt = ts("2024-01-01T12:00:00"),
        )
        val allocations = plan.residualAllocations()
        assertEquals(1, allocations.size)
        assertEquals(1, allocations.single().minutes)
        assertEquals(listOf(date("2014-01-02")), allocations.map { it.date })
        assertEquals(0, plan.residual!!.baseMinutes)
    }

    @Test
    fun `paged projection covers a long recipe exactly once`() {
        val plan = plan(
            authorizedDeltaMinutes = 10_000,
            previousObservationAt = ts("2014-01-01T12:00:00"),
            recoveryObservedAt = ts("2024-01-01T12:00:00"),
        )
        val recipe = plan.residual!!
        val pageSize = 97
        val pages = PlaytimeAllocationRecipes.pageCount(recipe, pageSize)
        assertTrue(pages > 30)
        val seen = LinkedHashMap<LocalDate, Int>()
        for (page in 0 until pages) {
            val rows = PlaytimeAllocationRecipes.page(recipe, page, pageSize)
            rows.forEach { row ->
                assertTrue("date ${row.date} appeared twice", seen.put(row.date, row.minutes) == null)
            }
        }
        assertEquals(10_000, seen.values.sum())
        assertEquals(plan.residualAllocations().sumOf { it.minutes }, seen.values.sum())
    }

    @Test
    fun `plan-level page returns only a bounded slice of a long window`() {
        val plan = plan(
            authorizedDeltaMinutes = 10_000,
            previousObservationAt = ts("2014-01-01T12:00:00"),
            recoveryObservedAt = ts("2024-01-01T12:00:00"),
        )
        val pageZero = plan.residualPage(0, 14)
        val pageOne = plan.residualPage(1, 14)
        assertTrue(pageZero.size <= 14)
        assertTrue(pageOne.size <= 14)
        assertTrue(pageZero.none { it.date in pageOne.map { p -> p.date } })
        assertTrue(pageOne.all { it.date.isAfter(pageZero.last().date) })
    }

    @Test
    fun `very large quantity over a long window does not overflow`() {
        val plan = plan(
            authorizedDeltaMinutes = 1_000_000_000,
            previousObservationAt = ts("2014-01-01T12:00:00"),
            recoveryObservedAt = ts("2024-01-01T12:00:00"),
        )
        assertEquals(1_000_000_000, plan.creditedMinutes)
        assertEquals(1_000_000_000, plan.residualAllocations().sumOf { it.minutes })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unrepresentable authorized sum is rejected`() {
        plan(
            authorizedDeltaMinutes = Int.MAX_VALUE,
            unplacedPendingMinutes = 1,
            previousObservationAt = ts("2024-01-10T12:00:00"),
            recoveryObservedAt = ts("2024-01-12T12:00:00"),
            existingContributionId = contribution(0, Int.MAX_VALUE),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `quantity range rejects an inverted cumulative range`() {
        PlaytimeQuantityRange(fromMinutes = 570, toMinutes = 500)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `recipe rejects out-of-window exclusions`() {
        ResidualDateRecipe(
            method = PlaytimeAllocationMethod.SINGLE_DAY_ESTIMATE,
            firstDate = date("2024-01-10"),
            lastDate = date("2024-01-10"),
            eligibleDateCount = 1,
            baseMinutes = 5,
            remainderCount = 0,
            totalMinutes = 5,
            excludedRanges = listOf(date("2024-01-01")..date("2024-01-02")),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `recipe rejects a non-conserving total`() {
        ResidualDateRecipe(
            method = PlaytimeAllocationMethod.SINGLE_DAY_ESTIMATE,
            firstDate = date("2024-01-10"),
            lastDate = date("2024-01-11"),
            eligibleDateCount = 2,
            baseMinutes = 3,
            remainderCount = 0,
            totalMinutes = 5,
            excludedRanges = emptyList(),
        )
    }

    // ---- provenance with an explicit injected evaluation date ----

    @Test
    fun `current single-day unknown time carries earned provenance`() {
        val plan = plan(
            authorizedDeltaMinutes = 70,
            previousObservationAt = ts("2024-01-09T12:00:00"),
            recoveryObservedAt = ts("2024-01-10T12:00:00"),
            evaluationDate = date("2024-01-10"),
        )
        assertEquals(PlaytimePlacementProvenance.SYNC, plan.provenance)
    }

    @Test
    fun `past same-day allocation carries retroactive provenance`() {
        val plan = plan(
            authorizedDeltaMinutes = 70,
            previousObservationAt = ts("2024-01-09T12:00:00"),
            recoveryObservedAt = ts("2024-01-10T12:00:00"),
            evaluationDate = date("2024-01-11"),
        )
        assertEquals(PlaytimePlacementProvenance.RETROACTIVE_PLAY, plan.provenance)
    }

    @Test
    fun `targeted past-day placement carries retroactive provenance`() {
        val plan = plan(
            authorizedDeltaMinutes = 50,
            previousObservationAt = ts("2024-01-08T12:00:00"),
            recoveryObservedAt = ts("2024-01-12T12:00:00"),
            knownPlayInstantAt = ts("2024-01-10T15:00:00"),
            evaluationDate = date("2024-01-12"),
        )
        assertEquals(PlaytimePlacementProvenance.RETROACTIVE_PLAY, plan.provenance)
    }

    @Test
    fun `timed placement on a past date carries retroactive provenance`() {
        val plan = plan(
            authorizedDeltaMinutes = 100,
            previousObservationAt = ts("2024-01-08T00:00:00"),
            recoveryObservedAt = ts("2024-01-08T01:00:00"),
            evidence = listOf(evidence("e", ts("2024-01-08T00:00:00"), ts("2024-01-08T01:00:00"))),
            evaluationDate = date("2024-01-09"),
        )
        assertEquals(60, plan.timedMinutes)
        assertEquals(date("2024-01-08"), plan.timedPlacements.single().date)
        assertEquals(PlaytimePlacementProvenance.RETROACTIVE_PLAY, plan.provenance)
    }

    @Test
    fun `current timed placement carries earned provenance`() {
        val plan = plan(
            authorizedDeltaMinutes = 100,
            previousObservationAt = ts("2024-01-08T00:00:00"),
            recoveryObservedAt = ts("2024-01-08T01:00:00"),
            evidence = listOf(evidence("e", ts("2024-01-08T00:00:00"), ts("2024-01-08T01:00:00"))),
            evaluationDate = date("2024-01-08"),
        )
        assertEquals(PlaytimePlacementProvenance.SYNC, plan.provenance)
    }

    @Test
    fun `whole plan is retroactive when a timed share lands on a past date`() {
        val plan = plan(
            authorizedDeltaMinutes = 100,
            previousObservationAt = ts("2024-01-07T00:00:00"),
            recoveryObservedAt = ts("2024-01-08T12:00:00"),
            evidence = listOf(
                evidence("past", ts("2024-01-07T12:00:00"), ts("2024-01-07T14:00:00")),
                evidence("current", ts("2024-01-08T10:00:00"), ts("2024-01-08T12:00:00")),
            ),
            evaluationDate = date("2024-01-08"),
        )
        assertTrue(plan.timedPlacements.map { it.date }.contains(date("2024-01-07")))
        assertTrue(plan.timedPlacements.map { it.date }.contains(date("2024-01-08")))
        assertEquals(PlaytimePlacementProvenance.RETROACTIVE_PLAY, plan.provenance)
    }

    @Test
    fun `continuing prior session start stays on the prior start date across midnight`() {
        val previous = ts("2024-01-08T23:55:00")
        val recovery = ts("2024-01-09T00:10:00")
        val plan = plan(
            authorizedDeltaMinutes = 15,
            previousObservationAt = previous,
            recoveryObservedAt = recovery,
            priorOpenSession = OpenSession(
                startAt = ts("2024-01-08T23:50:00"),
                minutes = 5,
                lastIncreaseAt = previous,
            ),
            evidence = listOf(evidence("c", previous, recovery)),
            evaluationDate = date("2024-01-09"),
        )
        val extend = plan.sessionActions.single() as SessionAction.Extend
        assertEquals(ts("2024-01-08T23:50:00"), extend.startAt)
        assertEquals(15, extend.addedMinutes)
        assertEquals(listOf(date("2024-01-08")), plan.changedDates().toList())
        assertEquals(PlaytimePlacementProvenance.RETROACTIVE_PLAY, plan.provenance)
    }

    @Test
    fun `a prior session started a different day is not extended into a same-day gap without continuity`() {
        val previous = ts("2024-01-09T11:55:00")
        val recovery = ts("2024-01-09T12:10:00")
        val plan = plan(
            authorizedDeltaMinutes = 15,
            previousObservationAt = previous,
            recoveryObservedAt = recovery,
            priorOpenSession = OpenSession(
                startAt = ts("2024-01-08T23:50:00"),
                minutes = 60,
                lastIncreaseAt = previous,
            ),
        )
        // The old row started yesterday; without coverage from its last increase to the new end it
        // is closed and a fresh same-day interval opens — the old row cannot absorb the midnight.
        val close = plan.sessionActions[0] as SessionAction.Close
        val open = plan.sessionActions[1] as SessionAction.Open
        assertEquals(previous, close.endAt)
        assertEquals(previous, open.startAt)
        assertEquals(recovery, open.endAt)
        assertEquals(15, open.minutes)
        assertEquals(15, plan.sessionMinutes)
        assertTrue(plan.changedDates().contains(date("2024-01-09")))
    }

    // ---- known target must stay inside the valid window ----

    @Test
    fun `known target after the recovery instant is ignored with conservative fallback`() {
        val plan = plan(
            authorizedDeltaMinutes = 20,
            previousObservationAt = ts("2024-01-08T12:00:00"),
            recoveryObservedAt = ts("2024-01-09T12:00:00"),
            knownPlayInstantAt = ts("2024-01-10T08:00:00"),
        )
        assertTrue(plan.diagnostics.any { it.code == PlaytimeDiagnosticCode.TARGET_OUTSIDE_WINDOW })
        assertEquals(
            listOf(date("2024-01-09") to 20),
            plan.residualAllocations().map { it.date to it.minutes },
        )
    }

    @Test
    fun `known target before the previous observation is ignored with conservative fallback`() {
        val plan = plan(
            authorizedDeltaMinutes = 20,
            previousObservationAt = ts("2024-01-08T12:00:00"),
            recoveryObservedAt = ts("2024-01-09T12:00:00"),
            knownPlayInstantAt = ts("2024-01-08T06:00:00"),
        )
        assertTrue(plan.diagnostics.any { it.code == PlaytimeDiagnosticCode.TARGET_OUTSIDE_WINDOW })
        assertEquals(
            listOf(date("2024-01-09") to 20),
            plan.residualAllocations().map { it.date to it.minutes },
        )
    }

    // ---- helpers ----

    private fun plan(
        authorizedDeltaMinutes: Int,
        previousObservationAt: Long?,
        recoveryObservedAt: Long = (previousObservationAt ?: 0L) + 60_000L,
        zone: ZoneId = utc,
        previousZone: ZoneId? = null,
        evaluationDate: LocalDate? = null,
        priorHighWaterMinutes: Int? = null,
        existingContributionId: PlaytimeContributionId? = null,
        priorOpenSession: OpenSession? = null,
        evidence: List<PlaytimeTimingEvidence> = emptyList(),
        consumedSpans: List<ConsumedPlaytimeSpan> = emptyList(),
        activeReaderIdentity: CloudReaderIdentity? = null,
        gapToleranceMillis: Long = PlaytimeAttributionPlanner.DEFAULT_GAP_TOLERANCE_MILLIS,
        clockAnomalous: Boolean = false,
        publicationDelayConflict: Boolean = false,
        knownPlayInstantAt: Long? = null,
        unplacedPendingMinutes: Int = 0,
    ): PlaytimeAttributionPlan = PlaytimeAttributionPlanner.plan(
        PlaytimeRecoveryRequest(
            account = "steam:1",
            appId = 1L,
            baselineEpoch = 7L,
            authorizedDeltaMinutes = authorizedDeltaMinutes,
            unplacedPendingMinutes = unplacedPendingMinutes,
            previousObservationAt = previousObservationAt,
            recoveryObservedAt = recoveryObservedAt,
            zone = zone,
            previousZone = previousZone,
            evaluationDate = evaluationDate,
            priorHighWaterMinutes = priorHighWaterMinutes,
            existingContributionId = existingContributionId,
            priorOpenSession = priorOpenSession,
            evidence = evidence,
            consumedSpans = consumedSpans,
            activeReaderIdentity = activeReaderIdentity,
            gapToleranceMillis = gapToleranceMillis,
            clockAnomalous = clockAnomalous,
            publicationDelayConflict = publicationDelayConflict,
            knownPlayInstantAt = knownPlayInstantAt,
        ),
    )

    private fun contribution(fromMinutes: Int, toMinutes: Int): PlaytimeContributionId =
        PlaytimeContributionId(
            account = "steam:1",
            appId = 1L,
            baselineEpoch = 7L,
            quantityRange = PlaytimeQuantityRange(fromMinutes, toMinutes),
        )

    private fun cloudEvidence(id: String, generation: Long) = PlaytimeTimingEvidence(
        evidenceId = id,
        appId = 1L,
        account = "steam:1",
        readerIdentity = CloudReaderIdentity("steam:1", generation),
        source = PlaytimeEvidenceSource.CLOUD,
        startAt = ts("2024-01-08T00:00:00"),
        endAt = ts("2024-01-08T01:00:00"),
    )

    private fun evidence(
        id: String,
        startAt: Long,
        endAt: Long,
        appId: Long = 1L,
        source: PlaytimeEvidenceSource = PlaytimeEvidenceSource.LOCAL,
        readerIdentity: CloudReaderIdentity? = null,
        gapFromAt: Long? = null,
        gapRecoveredAt: Long? = null,
    ) = PlaytimeTimingEvidence(
        evidenceId = id,
        appId = appId,
        account = "steam:1",
        readerIdentity = readerIdentity,
        source = source,
        startAt = startAt,
        endAt = endAt,
        gapFromAt = gapFromAt,
        gapRecoveredAt = gapRecoveredAt,
    )

    private fun ts(text: String, zone: ZoneId = utc): Long =
        LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun date(text: String): LocalDate = LocalDate.parse(text)
}
