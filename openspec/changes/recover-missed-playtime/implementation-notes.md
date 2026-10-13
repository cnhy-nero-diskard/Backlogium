# Implementation notes — recover-missed-playtime

Task 1.2 artifact. This file is the checked-in "direct session-quantity consumer and
conflicting-fallback-assertion" audit for `attribute-playtime-from-presence` referenced by
`tasks.md` 1.2. It maps every canonical quantity consumer to the planned canonical inputs of
this change's `design.md` and delta specs, and records the implementation base. It **does not**
change tasks, specs, code, or fixtures. Parent drives fixture implementation (task 1.1) and the
later task groups independently.

## Actual implementation base and schema versions

- **Branch / base**: work is on `feat/recover-missed-playtime`; this audit was performed at
   `4c6eee4a` (`docs: (allow backup format version 3 in backup-restore spec)`).
  `design.md` explored master at `8b481464`; nothing in the audit contradicts that exploration.
- **Room schema**: `app/src/main/java/com/example/backlogium/data/local/BacklogiumDatabase.kt`
  declares `@Database(version = 42)`, additive migrations through `MIGRATION_41_42`.
  Exported schemas exist through `app/schemas/com.example.backlogium.data.local.BacklogiumDatabase/42.json`.
  The next hop for the recovery tables (`design.md` §2) is **42 → 43**; task 2.4 exports the new
  schema and adds per-hop/composed-chain migration tests.
- **Backup format**: `BackupFile.CURRENT_FORMAT_VERSION = 2`
  (`app/src/main/java/com/example/backlogium/data/backup/BackupFile.kt`). v2 already carries
  `session.cloudContribution` (`recoveredSharedPlay`/`timingInformedSteamPlay`); `design.md` §10
  adds mandatory v3 recovery sections and rejects v3 under v2 markers.
- **Load-bearing existing symbols** (the defect surface and its constraints):
  - `Session` (`data/local/entity/Session.kt`) — `startAt` is mandatory; `endAt` nullable; `open`
    plus unique `openAppId` enforce the one-open-session invariant.
  - `Game.lastPlaytime` (`data/local/entity/Game.kt`) — today's only per-game quantity baseline,
    read via `GameDao.ownedGamesForDiffing()`.
  - `PlayerProfile.lastSyncAt` — becomes a new synthetic session's `startAt` through
    `PlaytimeObservationCommitter.commit` → `previousPollAt = lastSyncAt.coerceAtMost(observedPlayAt)`
    → `SessionDiffer.diff(previousPollAt = …)` → `SessionAction.Open(startAt = previousPollAt)`.
    This is the whole single-value defect (`attribute-playtime-from-presence/design.md`).
  - `SessionDiffer` (`domain/SessionDiffer.kt`) — stays as the authority for the per-game delta
    (`diff().playedDeltaByAppId`, high-water `newLastPlaytime`); its candidate *placement* is what
    this change re-routes (`design.md` §1).
  - `PlaytimeObservationCommitter.commit(...)` (`domain/PlaytimeObservationCommitter.kt`), the one
    raw path shared by the periodic poll (`work/SteamSyncWorker.kt:commitRawPoll`) and the targeted
    fetch (`work/PostPlaySyncWorker.kt`), with optional `CloudPresencePlaytimePlacement.Input`
    validated through `withValidatedPlacement` + `CloudPendingEvidencePruner`.
  - `attributeDailyProgress(...)` (`domain/DailyProgressAttribution.kt`) and
    `dailyProgressCorrections(...)` (`domain/DailyProgressBackfillUseCase.kt`) — today both
    reconstruct daily quantities from sessions (`startAt`-dated, then re-derived). Both are
    generalized to canonical dated activity (`design.md` §7).

## Planned canonical inputs (this change's vocabulary)

From `design.md` §1–§5, §7 and the delta specs — the targets every consumer below maps to:

| Planned record / projection | Where it is defined | Role |
| --- | --- | --- |
| Per-game recovery state | design §2 row 1 | account/app, baseline epoch, quantity high-water, last usable observation instant/date/zone, validity |
| Daily Steam snapshot | design §2 row 2 | one raw successful reading per game/date, regression-classified |
| Funded play contribution | design §2 row 3 | stable ID across account/app/epoch/(fromMinutes, toMinutes]; the *authorized* quantity |
| Timed contribution placement | design §2 row 4 | contribution → session share; frozen date/zone; evidence identity/quality |
| Unknown-time placement | design §2 row 5 | contribution/revision, date or pending state, minutes, method, frozen zone |
| Replacement journal | design §2 row 6 | operation/evidence identity, prior/current revisions, affected dates, finalization |
| Domain projections at the repository boundary | design §7 | `PlaySession`-style sealed activity: timestamped session / dated unknown-time allocation / pending date-unknown quantity; shared `CreditedMinutesByGame` (per-game total), `DatedCreditedTotals`, and per-date training eligibility for Personal Pace |

`design.md` §7 constraints that govern every mapping below: storage entities and DAO projections
stay out of `ui/` (the `CLAUDE.md` boundary grep), unknown-time allocations have **no**
`startAt`/`endAt`, allocations count in minutes/daily totals/quests/streaks/XP but **not** in
session counts, session length, or time-of-day, and an observation instant is never presented or
exported as a gameplay boundary.

## Consumer inventory: current `SessionDao` quantity reads → canonical input

Audit scope is every non-diagnostic reader of a `SessionDao`/`SessionRepository` quantity or
instant projection in `app/src/main`. Diagnostics (`ui/diagnostics/CloudPresenceDiagnosticsViewModel`,
uses `sessionsOverlapping`) is the documented `CLAUDE.md` exception and stays session-shape-only.

### 1. XP (cumulative per-game totals)
- `domain/GamificationUpdater.compute` — `sessionDao.trackedMinutesByGame()`, summed into
  `GamePlaytimeInput.minutesPlayed = backfillMinutes + manualSharedMinutes + tracked` (tapered
  against HLTB). `trackedByGame.keys + backfill + manual` defines the input universe.
- `ui/library/LibraryViewModel.xpInputs` + `xpContribution(...)` — the Library XP badge, same
  engine inputs (`backfillMinutes + trackedByGame[appId]`, NOT `playtimeForever`); the library rows
  display `backfill + tracked` XP contribution.
- `ui/gamedetail/GameDetailViewModel.Content.toSummary` — per-detail XP contribution, same sum.
- **→ Canonical input**: `CreditedMinutesByGame` (timed sessions + dated allocations + pending
  date-unknown quantity, exactly once per game) replaces `trackedMinutesByGame` as the `tracked`
  term in all three. Pending date-unknown participates in cumulative XP but not dated quests
  (playtime-recovery spec). `backfill`/`manual` stay separate frozen/estimated offsets.

### 2. Frozen lifetime backfill subtraction
- `domain/PlaytimeBackfillUseCase` — `backfillMinutes = max(0, playtimeForever − tracked(appId))`
  via `sessionDao.trackedMinutesByGame()`; reset path too.
- **→ Canonical input**: the subtraction must use `CreditedMinutesByGame` so a recovery bucket is
  never double-counted against a frozen offset (design §6/§7: "frozen lifetime-backfill
  subtraction"). Imported-balance transfers keep their own confirmation/journals; cumulative
  recovery never consumes a manual estimate or lifetime offset.

### 3. Daily progress raw credit and dates
- `PlaytimeObservationCommitter.commit` → `attributeDailyProgress(actions, …)` → `dailyProgressDao.addMinutes`
  (periodic and targeted share this path).
- `domain/DailyProgressBackfillUseCase` / `dailyProgressCorrections(sessions, …)` — a pure
  recompute over `sessionDao.getAll()` for the one-time day-attribution backfill and for
  `CloudPresencePlaytimeRefilingUseCase.recompute()`.
- `data/repo/SessionActionWriter.creditDailyProgress` (presence path).
- **→ Canonical input**: raw daily credit moves to the attribution plan's "raw daily credit"
  record; `dailyProgressCorrections` generalizes to canonical dated activity (sessions **and**
  allocations, with pending excluded from dated quests) and applies contribution deltas, not a
  full-history rewrite. Legacy rows keep existing correction policy (design §7).

### 4. History
- `ui/history/HistoryViewModel` — `sessionRepository.sessionsSince(cutoff)`, `earliestSessionStart`,
  `ui/history/HistoryGrouping.groupHistory(...)` (dates from `session.startAt` and daily progress).
- **→ Canonical input**: `sessionsSince` + a new dated-allocation projection; History groups by the
  canonical attributed date and can expand allocation-only game/day rows without clock boundaries
  (design §7; delta `app-ui/spec.md`). `earliestSessionStart` stays a *session* instant projection;
  paging needs a separate earliest canonical activity date that includes allocations, so allocation-only
  history and allocations preceding the first timestamped session remain reachable. Coordinate with `extend-history-
  before-tracking` (untracked evidence-only days) and `disclose-data-provenance` (source
  classification) — both are planning-only and must consume the same canonical projections when
  they land.

### 5. Analytics
- `ui/analytics/AnalyticsViewModel`:
  - quantity: `sessionRepository.minutesByGameBetween(...)` (per-game window totals for top games,
    comparisons, day breakdowns, headline) and `earliestSessionStart` (window anchor);
  - session-shape/time-of-day: `sessionRepository.sessionsBetween(...)` → `startAt` grouping,
    `SessionInsights`, `TimeOfDayPattern`.
- **→ Canonical input**: `minutesByGameBetween` and the daily chart become `DatedCreditedTotals`
  (allocations included); `sessionsBetween`, `sessionInsights`, and time-of-day stay session-only —
  the two are already fed by separate flows, which is exactly the split the recovery input needs
  (delta `app-ui/spec.md` "Unknown clock-time metrics"). `earliestSessionStart` remains
  session-only, while period availability uses the earliest canonical activity date including allocations.

### 6. Library badges and game detail
- `ui/library/LibraryViewModel` — `sessionRepository.trackedMinutesByGame` feeds the XP badge;
  `displayedPlaytimeMinutes(xp)` also uses `trackedByGame` for family-shared rows; sort and
  completion progress measure `playtimeForever` (owned) / tracked+manual (shared).
- `ui/gamedetail/GameDetailViewModel` — `trackedMinutesByGame[appId]` shown as "tracked" and used
  in XP contribution; `latestSessionAtByGame[appId]` becomes `lastPlayedAt` for family-shared rows.
- `ui/home/HomeViewModel` + `ui/home/HomeNextAction` — `trackedMinutesByGame` for goal/completion
  and collection cards.
- **→ Canonical input**: `CreditedMinutesByGame` for the tracked/badge figure and XP; owned rows
  keep Steam's `playtimeForever` as the display total. A recovered bucket increases the game
  total without fabricating a first/last session instant (design §7, `tasks.md` 5.7).

### 7. Collections, smart collections, home collection cards
- `ui/collections/CollectionViewModel` — `sessionRepository.trackedMinutesByGame` (playtime
  display), `sessionRepository.sessionCountByGame` (per-game `sessionCount` shown in the library
  metrics/collection rows).
- `domain/SmartCollectionFeed` — `trackedMinutesByGame`, `latestSessionAtByGame` (idle test /
  last-played join), `sessionCountByGame`; consumed by
  `ui/collections/SmartCollectionsViewModel` (sessionCount = times played).
- **→ Canonical input**: quantity via `CreditedMinutesByGame`; `latestSessionAtByGame` and
  `sessionCountByGame` must continue to reflect only timestamped sessions — collection session
  counts and recency exclude buckets (design §7). `GameSource.displayedPlaytimeMinutes` /
  `smartCollectionPlaytimeMinutes` keep their source split.

### 8. Recency and dormancy
- `data/repo/GameRepository.withHltb` → `LibraryRecency.derive(firstSessionAt = sessionRepository.firstSessionAtByGame[appId], …)`
  — the "newly played" signal fires once per game for life from the first session start.
- `work/SteamSyncWorker.recencyPollWrite(...)` — dormancy/return evaluation reads
  `sessionDao.latestSessionInstantByGame()` **before** the commit as `mostRecentSessionEndAt`.
- `SmartCollectionFeed` latest-at idle test and `GameDetailViewModel` shared `lastPlayedAt` use the
  same latest-instant projection.
- **→ Canonical input**: `firstSessionAtByGame` and `latestSessionAtByGame` stay **session-only**
  projections. Recency must not upgrade an allocation date or observation timestamp into an exact
  gameplay instant (design §7: "First/last-play recency must not convert an allocation date or
  observation timestamp into an exact gameplay instant"). Buckets change quantity only.

### 9. Gap plan inputs
- `domain/gapplan/GapPlanFeed` — `sessionRepository.trackedMinutesByGame` (all-time per game,
  makes shared playtime truthful) and `closedSessionsSince(affinityCutoff)` for genre affinity.
- `domain/gapplan/GapPlanEligibility` — `displayedPlaytime = source.displayedPlaytimeMinutes(playtimeForever, trackedMinutes, manualSharedMinutes)`.
- **→ Canonical input**: `CreditedMinutesByGame` for the eligibility/display quantity; genre
  affinity continues to train on actual dated sessions (`closedSessionsSince`) — allocations enter
  only via the same Personal Pace eligibility rules (design §7 "gap-plan inputs").

### 10. Personal Pace
- `data/repo/PersonalPaceRepository` — `sessionRepository.closedSessionsSince(cutoff)` →
  `PersonalPace.derive(PersonalPaceSession(startAtMillis, minutes, open), today, zone)`; consumed by
  Home and Collections (`CollectionViewModel.libraryMetrics`, `domain/CollectionSummary`,
  `domain/gapplan/GapPlanEngine`).
- **→ Canonical input**: the pure engine gains explicit per-date training eligibility (design §9):
  unresolved multi-day estimated dates are unknown for training and cannot synthesize zeros or
  satisfy the 14-covered/six-active confidence floors; fully resolved single-day date-known activity
  can train completed-date totals. Feed eligible dated allocations **and** timestamped closed
  sessions, with an eligibility mask, not raw bucket rows.

### 11. Backup export, validation, merge
- `data/backup/BackupExportMapper.buildExport`/`buildComputed` — reads `sessionDao.getAll()` and
  recomputes `trackedByGame` sums, per-game XP (`backupXpMinutes`) and the cumulative `xpTimeline`
  (`Session.startAt < cutoff` per day) inside one transaction snapshot.
- `data/backup/BackupValidation` — v1/v2 version checks incl. `session.cloudContribution`
  presence rules.
- `data/backup/BackupMergeEngine` — `mergeSession` by `(appId, startAt, endAt)` natural key,
  `recomputeAfterMerge` via `gamificationUpdater`.
- **→ Canonical input**: v3 adds mandatory recovery sections (observations, contribution/placement
  graph, replacement aliases/tombstones, frozen dates/zones) and computes rollups from canonical
  credited quantities (design §10). `backupXpMinutes` = backfill + manual + **credited** minutes.
  Legacy v1/v2 decode via the existing `BackupVersionedDecoder` rules; modern fields are rejected
  under legacy markers. Merge is contribution-aware over disjoint ranges.

### 12. Historical re-filing and reversal (attribute-playtime-from-presence's sweep)
- `domain/CloudPresencePlaytimeRefilingUseCase` — `cloudPresenceSessionRefiles(sessionDao.getAll(),
  ownedAppIds, intervals, range)` → `CloudPresencePlaytimePlacement.place(...)` sessions replacing
  whole rows; reversal keeps originals in DataStore and re-inserts them; recompute via
  `dailyProgressCorrections` + `RecomputeSource.RETROACTIVE_PLAY`.
- The separate `cloud_historical_*` tables (`CloudHistoricalOperation/Interval/Boundary/Journal`,
  apply payload) carry the durable acquisition and confirmation path for owned and shared games.
- **→ Canonical input**: re-filing must operate on contribution revisions and replacement aliases
  (design §6): move only unlocked estimated shares, reduce split sessions, preserve protected/
  manual placements and imported-balance transfers, and keep independent later upgrades when a
  reversal runs. Reversal must not resurrect superseded estimates or re-enable credited deltas.

### 13. Progress events and provenance
- `domain/GamificationUpdater.persist` / `ProgressEventDetector` / `ProgressMarksStore`,
  `domain/RecomputeSource` (`SYNC` = earned; `RETROACTIVE_PLAY`, `BACKFILL`, `RESTORE`, etc. =
  corrective baseline reseeds).
- **→ Canonical input**: current-date single-day unknown-time quality follows `SYNC`; multi-day /
  past-day / pending / evidence-upgrade / representation-only corrections use `RETROACTIVE_PLAY`
  (design §8). Mixed commits need a staged corrective-then-earned persist under the existing
  derived-write lock and journal, preserving owed events exactly once. Implementation gap to
  resolve in tasks 5.5/5.6: today `persist()` computes and persists one `GamificationResult` with
  one `source`; the staged protocol needs a corrective baseline pass excluding this operation's
  fresh earned inputs (incl. refreshed achievement XP) before the final `SYNC` pass.

### 14. Session rows themselves (writes), and the shared-game mechanism (unchanged surface)
- Writes: `data/repo/SessionActionWriter` (`applySessionActions`,
  `recoveryAfter`/`timingAfter` per-row `RecoveredSharedPlayState`/`TimingInformedSteamPlayState`),
  `SessionDao.insertOpenSessionIfAbsent`/`updateUnlessConflictingOpenSession`.
  Under the contribution-ledger model the two per-row states become derived denormalizations of the
  share table rather than the primary record (design §2: "A session can contain shares from several
  contributions; each share is linked separately").
- Family-shared games: `data/repo/CloudPresenceSessionIngestor` + `PresenceSessionRecorder` keep the
  presence mechanism; snapshot recovery SHALL NOT touch games without owned cumulative totals
  (playtime-recovery spec "Family-shared play").

## Conflicting fallback assertions in `attribute-playtime-from-presence`

The superseded contract: **when placement is unavailable, unconfigured, rejected, or covering
nothing, the whole Steam delta is written as one synthetic session whose `startAt` is the previous
`lastSyncAt`** — the defect `#181` describes. The recovery change replaces that fallback with local
unknown-time recovery and keeps a validated short-gap session only inside the 30-minute usability
bound (`design.md` §4; the existing `CloudPresencePlaytimePlacement.MINIMUM_PLACEMENT_PERIOD_MILLIS`
= 30 min constant is reused as the consultation/usability bound).

Tests that lock in the superseded behavior (audited on the implementation base):

| Test | What it asserts | Disposition under recover-missed-playtime |
| --- | --- | --- |
| `domain/CloudTimingContributionCommitTest.absentReaderWritesUnmarkedSession` | `commit(null)` writes one session row (20 min, `TimingInformedSteamPlayState.NONE`) | **Conflict.** The same increase becomes a dated allocation (or pending) instead of a synthetic session; task 3.2/6.1 fixtures replace this. |
| `domain/CloudTimingContributionCommitTest.unchangedSuggestionAndRejectedCoverageDoNotClaimTiming` | rejected/UNKNOWN coverage → unaided session, `NONE` claimed | **Conflict in the rejected-coverage half**: rejected evidence routes to local unknown-time recovery (recover cloud-presence-reader spec); it must stay *unmarked timing* but stop being a timestamped session. Zero-delta close behavior stays. |
| `domain/CloudTimingContributionCommitTest.laterUnaidedMinutesMakePreviouslyFullTimingPartial` | 20 unaided minutes extend the existing row at its old `startAt` | **Conflict.** A stale open row is closed at its last trustworthy boundary and the new delta becomes recovery (design §1: "do not append across an uncovered gap"); the FULL→PARTIAL transition moves to share accounting. |
| `work/PostPlaySyncWorkerTest` "later minutes remain on the unaided t0-based session" (≈line 404/426) | after a periodic commit, later minutes keep `startAt == longGapStart`, session minutes 50 | **Conflict.** This is the #181 defect verbatim; design §4/§6 replace it (stale-open closure + recovery buckets for the later delta). |
| `work/PostPlaySyncWorkerTest.rejectedPlacementEvidenceFallsBackToTheFullDeltaAndItsOriginalDate` | rejected gap → full 30 min in a single session dated `oldStart` (2026-07-26) and that date's daily progress | **Conflict.** Rejected timing means local recovery: same-day/consecutive date → unknown-time credit on the recovery date rules; multi-day → even split, never the old-start synthetic date. |
| `data/repo/CloudPresencePlacementReaderTest.staleTerminalWindowEndBeforePeriodEndRefusesPlacement` and `cursorAdvancedInsideSteamPeriodRefusesSuffix` | reader gate returns `null`, leaving "the ordinary unaided attribution" | **Semi-conflict.** The `null` gate itself is kept; the described downstream ("unaided attribution" = synthetic session) is replaced by local recovery. Assertions on `null` survive; comments/contract prose and downstream-fixture expectations change. |
| `domain/CloudPresencePlaytimePlacementTest.noCoveringIntervalFallsBackWithoutProducingOutOfCoverageActions`, `zeroIncreaseFallsBackWithoutPlacement`, `unknownAndZeroSpansFallBackWithoutPlacement` | pure rule returns `null` (no out-of-coverage actions) | **Compatible.** The placement rule's `null` return is retained exactly (`null` means "caller uses recovery", not "caller writes the old synthetic session"); caller routing changes. |
| `work/PostPlaySyncWorkerTest.longGapTargetedFetchStillSucceedsWhenOptionalReaderFails` | reader throws → sync succeeds, 30 minutes credited | **Compatible on quantity** (local recovery still credits 30); row shape becomes allocation/bucket, so the fixture's session assertion is updated. |
| `work/PostPlaySyncWorkerTest.shortTargetedFetchDoesNotConsultTheOptionalPlacementReader` | short gap → no read, ordinary session at `shortGapStart` | **Compatible.** Short-gap validated timing and the 30-minute bound keep ordinary session behavior (recover steam-sync spec, playtime-recovery spec). |
| `domain/SessionDifferTest.*` (many) | `SessionAction.Open.startAt == previousPollAt` for the presented `previousPollAt` | **Compatible.** `SessionDiffer` is untouched (design §1); the caller (`PlaytimeObservationCommitter`) decides whether an Open action is discarded in favor of buckets. |
| `work/SteamSyncDayAttributionTest.*` | session start-date attribution of credited minutes | **Compatible.** Start-date attribution stays for timestamped sessions; allocations use explicit frozen dates (recover steam-sync spec "Unknown-time allocation date"). |

## Coverage summary

- **Mapped to canonical inputs**: XP (`GamificationUpdater`, Library badge, game detail XP),
  frozen backfill subtraction (`PlaytimeBackfillUseCase`), daily raw credit and corrections
  (`attributeDailyProgress`, `dailyProgressCorrections`), History (`HistoryViewModel`/`groupHistory`),
  Analytics (`AnalyticsViewModel`), Library/detail, collections/smart collections/home collection
  cards, recency and dormancy (`GameRepository.withHltb`, `recencyPollWrite`), gap plan, Personal
  Pace, backup (export rollups, validation, merge), historical re-filing/reversal, and progress
  events/provenance. Diagnostics remain session-only by documented exception.
- **Conflicting fallback assertions**: identified above; concentrated in
  `CloudTimingContributionCommitTest`, `PostPlaySyncWorkerTest` (3 cases), and the prose contract of
  `CloudPresencePlacementReaderTest`'s refusal tests. The pure `SessionDifferTest` start-date
  assertions and the `CloudPresencePlaytimePlacementTest` null-return tests are compatible; only the
  caller-side handling of `null` changes.
- **No blockers for task 1.2.** The audit is planning-only. Real integration work ahead (not
  blockers): `Session.startAt`-based projections need parallel canonical dated projections;
  `GamificationUpdater.persist` needs the staged corrective/earned protocol for mixed commits
  (design §8); per-row `TimingInformedSteamPlayState` becomes a share-derived denormalization;
  `BackupExportMapper.xpTimeline` reconstructs cumulative XP from `session.startAt` cutoffs and must
   switch to canonical dated totals in v3; Personal Pace's engine input gains an eligibility mask
  without densifying estimated dates.
- **Coordination**: `disclose-data-provenance` and `extend-history-before-tracking` are both
  planning-only (all task boxes unchecked) and are not dependencies; the read models this note
  names are the shared targets so those changes can land independently.
- **Fixtures**: task 1.1 fixtures are implemented by the parent independently of this note.
