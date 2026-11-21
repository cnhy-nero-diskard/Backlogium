## Context

See proposal.md for the priority and scope. This change depends on the completed [archived recovery design](../archive/2026-10-17-recover-missed-playtime/design.md), its [implementation notes](../archive/2026-10-17-recover-missed-playtime/implementation-notes.md), and current canonical `playtime-recovery`, `steam-sync`, `backup-restore`, `progress-events`, `cloud-presence-reader`, `app-ui`, `personal-pace-forecasting`, `live-status`, and gamification requirements. The archive is historical evidence, not an edit target. Older fallback assumptions in the still-open `attribute-playtime-from-presence` change were superseded by recovery; its unrelated outstanding live-cloud tasks are not automatically completed here. `disclose-data-provenance` and `extend-history-before-tracking` are not dependencies.

The inspected base is current local/remote `master`, `96af1b72af83a6c5349374710d3e4691fa326b22`, on 2026-10-19. The retained feature branch's local and remote tips were both `93716a0942c99848725c539467cd098a6795a539` before planning. New work uses `fix/harden-playtime-recovery-post-merge`; preservation means no operation on the retained branch, not merely keeping its name.

### Responsibility and transaction map

| Component | Responsibility and boundary |
| --- | --- |
| Periodic `SteamSyncWorker` / targeted `PostPlaySyncWorker` | Fetch and validate owned cumulative readings; enforce active account and reader/schedule generations; call the shared raw commit and derived tail. Network acquisition is not quantity authorization. |
| `PlaytimeObservationCommitter` / `SessionDiffer` | Read per-game baseline inside the raw Room transaction; authorize only a positive high-water increase; select one pure placement plan for both observers. |
| `PlaytimeAttributionPlan` / commit adapter and mapper | Plan bounded evidence shares, exact fallback or pending quantity; translate to stable contribution/placement identities and raw daily credit. |
| `PlaytimeRecoveryDao` | Enforce account identity, non-overlapping funded ranges, current revisions, session-share bounds, conservation and exact recipe prefixes; atomically persist observations, funding and raw placement. |
| `RecoveryPlacementReconciler` / use case | Replace unlocked placement of existing funded quantity, subtract consumed evidence, preserve protected/legacy shares, journal corrections and use the same derived engine. |
| `PlaytimeReadProjector` / `SessionRepository` adapters | Project canonical quantity and dated activity, accounting for mixed legacy session remainder and recipe tails; expose domain models rather than storage entities. |
| `UnknownTimeMaterializer` / daily-credit coordinator | Materialize bounded recipe slices and operation-owned raw date deltas without double credit; retain a durable cursor. |
| `RecoveryDerivedFinalizer` / `GamificationUpdater` | Resume corrective then earned stages through the existing derived-write lock and WAL; preserve event eligibility and acknowledgements. The on-device engine remains the sole derived-state author. |
| Backup validator / merge planner / recovery store | Preflight the whole file, merge overlapping funded ranges and replacement aliases, preserve eligible baselines, and atomically commit restore before local recomputation. |

```text
trusted periodic or targeted Steam reading
                    |
account / reader admission -> Room transaction
                    |
per-game high-water -> sole delta -> attribution plan
                    |
baseline + snapshots + funded contribution + placements + raw daily credit
                    |
bounded recipe materialization -> durable corrective / earned finalization
                    |
coherent canonical projections -> XP / History / Analytics / pace / other consumers
```

### Evidence inventory at proposal time

- Source inspection confirms shared quantity authorization and explicit conservation/replay guards. This is evidence of mechanisms, not proof that every interleaving is safe.
- The preceding exploration on this same base ran five existing unit-test classes: `PlaytimeAttributionPlanTest` (66), `PlaytimeObservationCommitterRecoveryTest` (12), `RecoveryDerivedFinalizerTest` (13), `SessionRepositoryRecoveryProjectionTest` (15), and `BackupRecoveryRoundTripTest` (8): **114 passed, no failures/errors/skips**, using `:app:testDebugUnitTest` with these five `--tests` filters and `--max-workers=1`. Host: Windows/PowerShell with the configured Android Studio Java runtime and process-local `ANDROID_HOME=C:/Users/cnhyn/AppData/Local/Android/Sdk`. The implementation audit must record exact runtime/SDK versions afresh.
- [Merge-head CI run 39002809566](https://github.com/cnhy-nero-diskard/Backlogium/actions/runs/39002809566) succeeded for Android unit tests, lint, screenshot verification and migration instrumentation, plus the independent script/functions jobs. It verifies that head, not future hardening commits.
- Archived notes record earlier broad-run DataStore failures and isolated reruns; subsequent PR/merge CI was green. Treat historical failures as investigation inputs, not current reproduced failures. The archived 55-minute Invisible-mode acceptance is **user-reported** and does not close this change's device/manual gate.
- No new full local suite, device exercise, production-data performance benchmark, streaming-update reproduction, or worker-finalization interleaving reproduction was performed for this proposal.

## Goals / Non-Goals

**Goals:** produce an auditable closure for every high-risk observation; align backup input validation with storage; guarantee valid canonical snapshots and earned/corrective isolation; retain quantity and uncertainty semantics; measure and reduce unnecessary work with reviewable evidence.

**Non-Goals:** change XP formulas, rounding or date policy, confidence floors, cloud authorization, protected/manual history, event vocabulary, or ownership semantics. No speculative ledger replacement, global lock around network requests, schema downgrade, automatic data repair, arbitrary LOC target or dependency on unrelated feature delivery.

### Invariants retained throughout

1. `SessionDiffer` remains the only owned-game quantity detector. Timing evidence and live timers cannot mint quantity; shared games keep their separate evidence/manual mechanism.
2. Each authorized contribution conserves **active funded timed shares + dated unknown-time shares + pending date-unknown quantity**, including logical recipe residual exactly once. All-time reads count contributions plus only unlinked legacy session remainder.
3. First baselines do not import lifetime play. Successful unchanged reads, regressions, malformed/missing fields, stale responses, account changes and shared-to-owned conversion retain their canonical policies.
4. Date/zone keys are frozen. One-day/no-evidence fallback, multi-day exact earliest-first residual rounding, partial coverage and pending invalid windows do not invent clock boundaries or zero activity rows.
5. Revisions replace existing placement, never award another delta. Protected, imported, manual and unprovable legacy state is not silently rewritten; historical apply/reversal cannot resurrect superseded shares.
6. Raw baseline/funding/placement/daily credit commits atomically. Failed writes leave quantity retryable; recipe materialization and derived finalization resume durably without partial/double credit.
7. Dated estimates feed appropriate totals, XP, quests and streaks; pending quantity feeds cumulative totals but no dated quest. Unknown time supplies no session-shape/time-of-day/recency evidence; multi-day estimates neither train Personal Pace nor manufacture covered/active confidence dates.
8. Corrections preserve XP under unchanged quantities/rules, longest-streak protections, owed events and acknowledgements. Fresh eligible play/achievement inputs retain earned-event eligibility exactly once.
9. Active-account, reset/logout and reader-generation fences prevent stale workers and foreign detector baselines from authoring quantity. Confirmed cross-account history imports follow existing warning/merge policy; inconsistent account links are rejected, not all foreign history indiscriminately banned.
10. Compatible v1/v2 imports and v3 round trips retain provenance and uncertainty. Rollback cannot discard recovery tables, funded ranges or backup sections.

## Decisions

### 1. Triage before refactoring; keep the evidence register explicit

The following is a **risk-ranked starting checklist**, not completed audit work or proof of user-visible corruption. P1 labels below denote investigation priority/possible impact unless explicitly confirmed. All items remain open until their evidence-backed disposition is recorded in the final audit note.

| ID / order | Classification | Evidence / hypothesis | Required disposition |
| --- | --- | --- | --- |
| D1 / first, P1 | **Verified code-level defect: validation contract mismatch** | `BackupRecoveryValidation.kt:87,137` rejects negative epochs but accepts zero; `PlaytimeRecoveryDao.kt:263,707` requires positive contribution epochs. Same-account observation epochs can be stored by the restore adapter. Confirmed inconsistency; no end-to-end corruption or failing import fixture has yet been demonstrated. | Reproduce preflight acceptance of zero observation and contribution epochs, then exercise real import/next sync. Reject unusable state before mutation; positive fixtures, rollback and existing saves remain valid. |
| R1 / first, P1 candidate | **Unverified correctness risk: reactive snapshots** | `SessionRepository.kt:132` independently combines session, contribution and funded-share flows. Windowed projections also combine recipe/allocation/host inputs. A valid write might publish mismatched snapshots and throw or temporarily inflate totals; settled `.first()` projection tests do not prove update safety. | Collect continuously through barrier-controlled inserts/extensions/revisions/materialization/reset. Prove each emission coherent or reproduce and fix; cover all-time, dated and training projections plus one-shot reads. |
| R2 / first, P1 candidate | **Unverified correctness risk: finalization interleaving** | `SteamSyncWorker.kt:444-525` and `PostPlaySyncWorker.kt:289-397` release/reacquire the sync barrier between raw commit and finalization. Startup resumption precedes fetch, not necessarily every competing raw commit. An earlier corrective pass might absorb another operation's earned input. | Control two workers around raw commits and derived stages using the real engine/WAL. Prove fresh play and achievements retain eligibility after overlap, crash, ack, config/date change and restart; fix only on evidence of a gap. |
| R3 / next, P1 candidate | **Unverified boundary risks adjacent to D1** | Observation high-water validation checks nonnegativity; live detector fields are Int-bounded. Observation/contribution coherence, account links, epochs, integer edges and legacy restore can diverge. No failure from these cases is claimed yet. | Boundary/replay fixtures using real restore followed by Steam reads; retain supported history and reject unusable detector state without truncation. Specify any intentional compatibility change before implementation. |
| F1 / next, performance | **Verified source pattern; performance impact unmeasured** | `PlaytimeRecoveryCommitAdapter.kt:116,152` traverses historical contributions and queries allocations/placements per contribution. `RecoveryPlacementReconciler.kt:683-696,909-929` rereads all sessions and rescans same-game peers per contribution. Materialization/export also warrant measurement. | Capture query/row/write counts and durations, identify avoidable growth, then batch or narrow reads and compare before/after. Do not call this measured latency or a correctness defect. |
| O1 / last, optional | **Optional cleanup; no defect asserted** | Recipe arithmetic, mappings and responsibilities span large files; size alone does not establish redundancy. | Inventory duplicate logic/leaky boundaries; extract only a measured or correctness-relevant cohesive unit. Explain deliberately retained complexity; track cosmetic follow-ons separately. |

New findings must record category, severity rationale, source/head, failing input or schedule, expected/actual outcome, test/evidence link, remediation or safety proof, owner/blocker and retest head. Do not promote a hypothesis merely because a nearby test passes. Rejected hypotheses receive a narrowly stated safety proof, not an unqualified claim about all execution paths.

**Alternative rejected:** broad class splitting or rewriting all ledgers before reproductions. It expands the regression surface without addressing the highest-risk boundaries.

### 2. Align backup preflight at the existing boundary

Add explicit positive-epoch preflight for both observation and contribution records. Use real full-file validation and transactional restore tests to prove zero/negative epochs fail before writes and positive states remain importable. An observation-only invalid baseline must not poison a later sync. Audit adjacent numeric/identity constraints under R3; do not silently clamp imported values or erase newer local recovery state. Preserve v1/v2 compatibility and the existing confirmed cross-account warning/history policy while ensuring foreign baselines never become active.

Conserved historical graphs may lack or outlive their observation row. Preserve them through export, full-file validation and restore without synthesizing an observation. Enforce endpoint coverage when a matching observation exists. A restored game skeleton without a usable local detector baseline must take its first live reading as a baseline even on an already-synced account; start a fresh epoch above the retained historical epochs so a lower first reading leaves that history exportable.

A trustworthy restored per-game observation remains authoritative even when the receiving profile has never synced. Its first higher Steam reading must authorize only the increase over its saved high-water; the placement planner uses that game's saved observation window rather than an epoch-zero account timestamp. Games without such a baseline still establish one without lifetime credit in the same poll.

Prefer a narrow shared validation rule only if it prevents demonstrated divergence; keep file-specific diagnostics and storage enforcement. Do not add a validator abstraction just to deduplicate a comparison. No blanket sanitization of malformed saves.

### 3. Require coherent read revisions, choose the smallest proven fix

For R1, use deterministic collection fixtures with observers attached **before** transactions and assert every emission, not just eventual totals. Include session growth, timing replacement, recipe cursor advancement, account reset, hidden-state changes, frozen dates across timezone shifts and mixed legacy remainder. Distinguish real invalid persisted graphs (which must still be rejected) from valid writes seen through torn reads.

If reproduced, prefer transactionally read immutable projection bundles behind a single invalidation source, or an equivalent narrow database projection providing the same snapshot guarantee. A transaction around each independent query does not make separately emitted flows one snapshot. Preserve bounded window queries, targeted host/share loading and domain repository boundaries. Never mask the problem with `catch`, clamping, dropping funded shares or arbitrary delays. Keep the existing implementation if a controlled proof establishes coherence for the relevant schedules.

### 4. Protect operation boundaries without changing earned policy

For R2, reproduce the candidate schedule: operation A commits corrective/mixed raw input; operation B commits fresh eligible play/achievement input before A finalizes; A resumes correction; B finalizes. Reverse observers/order and insert process-death failpoints before/after each durable raw, corrective persist, earned persist and stage mark; acknowledge a delivery before retry.

If the gap is real, prefer draining pending operations immediately before a competing raw commit and keeping raw commit through its materialization/finalization under the existing account/sync barrier where lock ordering permits. An equivalent explicit operation-input isolation is acceptable only if deterministic tests prove the same semantics. Keep Steam/network fetches outside that extended boundary. Observe the established sync/account -> reader-generation -> Room order and existing derived-write ordering; document any changed order and test reset/restore/configuration writers for deadlock and event loss. Process-local serialization must not replace database quantity guards. A future multi-process requirement is not a reason to weaken today's durable replay correctness.

**Alternatives rejected:** suppress all earned events in mixed syncs, use a second progression engine, or globally serialize network requests to conceal a race.

### 5. Measure before batching and retain the right complexity

Use seeded, non-personal datasets with recorded row counts and activity distribution: approximately 250/1,000/3,000 games and 5,000/25,000/100,000 contributions, including quiet games, mixed legacy/funded sessions, superseded revisions and partial/complete recipes. Include a compact ten-year gap with both one-minute and dense residual cases. State why the selected sizes represent realistic and stress histories; adjust with documented evidence rather than requiring production data.

Measure unchanged library sync, one-game increase, repeated/duplicate delayed evidence, recipe resumption, backup export/import and a 30-day History/Analytics window. Record query count, rows read, rows written, transaction duration, end-to-end duration, window-query scope, and peak memory where available. Separate network-free commit/reconciliation costs from Steam latency. Use the same build, device/runtime, fixture seed and instrumentation for baseline/after, one warm-up and at least five measured runs; report medians and ranges, not a one-off stopwatch value.

For repeated evidence over C contributions, identify per-contribution peer lookups and duplicate full-session scans explicitly. Prefer account/game/window-scoped batch reads, session-ID queries and reusable pure range helpers where justified; a transaction-scoped cache cannot outlive its snapshot. Acceptance is elimination or evidence-backed justification of avoidable repeated scans, no new quantity/provenance differences, and no unexplained material regression in any measured workload. Do not invent a device-independent millisecond budget. Add an index only when a query plan/measurement supports it, with an additive migration.

### 6. Use small review units and retain an audit trail

Keep D1 and its regression as the first independently reviewable implementation unit. Isolate confirmed R1/R2 repairs and F1 improvements where separable; inseparable fixes can share one focused PR with an explanation. Every unit links #183, this change, the finding ID and its tests. No commits/pushes/PR creation occur in this planning turn. Future delivery authorization follows the implementation workflow chosen by the user.

Maintain `audit-notes.md` during implementation with the data-flow map, disposition of each register item, counterexamples/safety proofs, before/after measurements, retained complexities, commands/environments/head SHAs and remaining blockers. Evidence from a device/user is labelled by its source. This note must end with the gate matrix below; do not create a fictitious completed audit now.

### 7. Test matrix and full acceptance coverage

| Issue area | Required fixtures / observations | Tasks |
| --- | --- | --- |
| A: authority, transaction and repeat safety | Periodic/targeted same and different readings, both commit orders, separate coordinator instances, retry/rollback at each raw write, unchanged/regressing/missing/malformed/negative responses, per-game boundaries, first baseline, stale older responses. | 1.1-1.3, 3.1-3.3 |
| A: placement/conservation | Tue 500 -> Wed 570 = Wed 70; Mon 500 -> Fri 605 = 27/26/26/26; 35 timed + 70 residual = 105; two minutes/four dates; no/partial/mixed evidence, duplicates/overlaps/wrong generation, consumed spans, delayed/replaced/protected placement and historical re-filing/reversal. | 3.2-3.5 |
| A/C: time, lifecycle and sharing | Same-day 30-minute and rounding boundaries, date rollover, DST/leap/timezone freeze, inverted/no-anchor pending windows and later valid evidence without borrowing an unrelated window, long gaps and zero shares; process death, reset/logout/account switch, shared/borrowed and shared-to-owned conversion. | 3.3-3.5, 4.1-4.4 |
| A/D: downstream totals | Old/imported/manual/backfill plus mixed funded/legacy rows; XP/level, quest flips, daily Focus/visibility semantics, streaks/longest streak, Library/detail/collections/game badges, analytics, exact recency/session counts and owed events remain consistent. | 3.5, 4.4, 5.1-5.4, 7.1-7.3 |
| B: architecture/performance | Seven named responsibilities and adapters, duplicate arithmetic/state, layering/cycles/wrappers, transaction breadth, N+1 scans, cache/recipe coherence; realistic repeated workloads and database write amplification with baseline/after metrics. | 1.2, 6.1-6.4 |
| C: persistence/backup | Real 42->43 and complete supported upgrade chain, constraints/indexes/legacy rows, rollback/reentrancy; v3 round trip and merge, v1/v2 imports, malformed/account-inconsistent references, foreign inactive baselines, overlap partitioning/tombstones, idempotent restore then sync. | 2.1-2.4, 4.1-4.4 |
| D: UI/pace | Allocation-only/mixed/single-day/multi-day/pending/unavailable states; no fabricated clocks, accessible ! with retained window/zone and day share, font scale; light/dark x narrow/standard; no unknown-time shape/time-of-day input; multi-day dates cannot train pace or inflate confidence, including zero-share dates and late full resolution. | 7.1-7.3 |
| E: regression/delivery | Real-engine/WAL failure injection, actual emulator/device migration and History/Analytics instrumentation, offline/restart/Invisible and multi-day manual acceptance; full unit/lint/Roborazzi gates on stabilized head, flaky/OOM investigation, remote CI and named infrastructure blockers. | 5.1-5.4, 8.1-8.6 |

Verification commands (PowerShell, from the implementation checkout; record actual JDK, SDK/API, device image/serial or anonymized identifier and revision):

```powershell
$env:ANDROID_HOME='C:/Users/cnhyn/AppData/Local/Android/Sdk'
.\gradlew.bat :gamification:test :app:testDebugUnitTest
.\gradlew.bat test :app:compileReleaseKotlin
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:verifyRoborazziDebug --tests com.example.backlogium.ui.screenshot.RoborazziSpikeTest --tests com.example.backlogium.ui.screenshot.MainScreenScreenshotNarrowTest --tests com.example.backlogium.ui.screenshot.MainScreenScreenshotStandardTest
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.example.backlogium.data.local.MigrationTest,com.example.backlogium.ui.history.HistoryScreenTest,com.example.backlogium.ui.analytics.AnalyticsScreenTest'
openspec.cmd validate harden-playtime-recovery-post-merge --strict --no-interactive
git diff --check
```

The first command is the full focused Android/gamification gate; `test` plus release compilation is the CI-equivalent broader gate. Review failures and rerun the full affected gate after a repair; an isolated passing class is diagnosis, not evidence that a failed full suite became reliable. Resolve cached/up-to-date ambiguity with a recorded fresh full test execution on the final head. Screenshot verification follows `docs/visual-regression-screenshots.md`; record/review only deliberately changed baselines in the same UI change. Compare the documented UI storage-boundary scan with the base so unrelated pre-existing breaches are not silently bundled into hardening.

### Completion gate (all remain open at proposal time)

| #183 acceptance condition | Required closing evidence | Tasks |
| --- | --- | --- |
| 1. Dedicated change, checked-off tasks and linked implementation PR(s) | This change's tasks complete only for performed work; reviewable PR links recorded in audit note. Planning readiness alone is not delivery. | 9.1, 9.2 |
| 2. Data flow/findings documented; every high-risk observation fixed or proved safe | Finding-by-finding register with reproducible evidence and explicit disposition, including newly discovered items. | 1.1-1.3, 9.1 |
| 3. No unresolved known loss, duplicate credit, account isolation, backup corruption or reconciliation defects | Defect regressions pass and unresolved known P0/P1 recovery defects are absent; unresolved high-risk cases keep the gate open. | 2-5, 8.6, 9.2 |
| 4. Quantity, uncertainty/provenance and consumers match #181/#182 | Invariants/matrix verified; any intentional behavior change is called out and specified before shipping. | 3.1-3.5, 4.4, 5.1-5.4, 7.1-7.3, 9.1 |
| 5. Relevant Android/CI/device evidence, or named blocker still open | Full stabilized-head suites, emulator/device and manual records plus live CI for that head. Infrastructure issues are independently tracked; critical unverified tests mean this gate and #183 stay open. | 8.1-8.6, 9.2 |
| 6. Justified complexity/performance scope | Methodology and baseline/after measurements, unchanged semantics, retained-complexity rationale, minor cleanup separately tracked without holding stabilization hostage. | 6.1-6.4, 9.1 |
| 7. Retained branch untouched; no unrelated work | Local/remote retained ref and history checked against initial evidence, scoped final diff/PRs, no prohibited branch operation. | 1.1, 9.2 |

## Risks / Trade-offs

- Coherent snapshot loading can increase transaction duration -> retain bounded projections, measure lock time and compare UI workloads; never obtain a snapshot with network work inside Room.
- Longer commit-to-finalize serialization can delay other writers or invert locks -> document ordering, use controlled lock/reset/config tests, and measure commit/finalize duration; preserve the existing WAL and account barrier.
- Earlier rejection of malformed backup data can expose previously tolerated saves -> pin the change to demonstrably invalid state, test positive and legacy fixtures, report diagnostic detail and leave the receiving database untouched; do not silently repair or truncate history.
- Performance fixtures can reward the wrong optimization -> document distributions and query/write counts across normal and stress workloads, including unchanged sync and UI windows, rather than only one large reconciliation case.
- Green focused tests/old CI can be mistaken for stabilization -> keep evidence tied to commands, environment and head, require full fresh gates and actual device/manual work, and leave blocked acceptance unchecked.
- Some apparent duplication encodes distinct provenance/migration semantics -> preserve it unless equivalence is demonstrated and document why it remains.

## Migration Plan

1. Confirm current `master`, clean/scoped workspace, initial retained refs and the archived dependency; keep planning on the new branch. Do not modify the archive or canonical specs directly.
2. Reproduce D1 and audit R1-R3 before application edits. Ship narrow preflight changes and evidence-supported correctness fixes with regressions, preserving all stored quantity and supported backup versions.
3. Prefer query/coordination changes compatible with Room v43. If measurements justify a new index/table/constraint, use the next additive schema version from the actual implementation base, export the schema and test the new hop plus full chain. Never rewrite the shipped 42->43 migration to conceal the change.
4. Run the complete matrix and stabilized-head gates; create the final audit note and linked review units. Do not close #183 while a critical check or high-risk disposition is outstanding.
5. Roll back a behavioral/query change by reverting its scoped code while retaining canonical readers and recovery tables/graphs. A rollback after a schema change must remain compatible with the newer database; no downgrade/destructive reset and no v2 export that omits v3 recovery state. Any required repair or lossless compatibility migration is a separately reviewed plan with rollback evidence.

## Open Questions

- Do controlled update schedules reproduce R1 or establish safety for all targeted projections? Either result follows the same investigation/disposition tasks and specified snapshot contract.
- Does the candidate overlapping-worker schedule reproduce R2 with the real WAL, or do existing fences prevent it? This decides whether a repair is needed, not whether earned eligibility is required.
- Which measured dataset/workload shows the largest avoidable query/write amplification, and does it justify an additive index? Measurements choose the smallest optimization under the defined compatibility policy.

These are audit outcomes, not permission to defer the acceptance gate or change the accepted recovery policy.
