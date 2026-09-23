## 1. Durable contribution evidence

- [ ] 1.1 Add nullable, type-safe cloud contribution provenance to the session ledger and migrate older rows to unknown; verify a Room migration test opens a pre-change database without changing session times or minutes.
- [ ] 1.2 Project provenance through `SessionRepository` as a domain value with no Room imports in product UI; verify a repository test distinguishes unknown, recovered, timing-informed, and partial contributions.
- [ ] 1.3 Record recovered-play provenance only when cloud ingest actually credits shared-game play through the existing writer; verify tests for accepted, rejected-gap, duplicated, and mixed live/cloud observations.
- [ ] 1.4 Record timing provenance only when cloud placement changes the unaided owned-game session actions, in the existing atomic commit; verify tests for changed actions, unchanged actions, rejected coverage, no reader, and exact Steam-owned minute totals.
- [ ] 1.5 Carry existing provenance across open-session extension and closure, distinguishing local-then-cloud and cloud-then-local partial results; verify neither order claims full cloud recovery when local play contributed.
- [ ] 1.6 Preserve original provenance when applying and reversing a historical re-file; verify a re-file/reversal test restores both ledger attribution and the original contribution classification.

## 2. Routine reader and admission policy

- [ ] 2.1 Add a bounded, account-fenced multi-page routine read through the existing serialized repository and ingest callback; verify a paginated test consumes through the terminal page without another reader stealing its cursor.
- [ ] 2.2 Bound one routine attempt to four pages, persist the consumed position page by page, and report partial/failed/complete/no-new-data outcomes; verify restart after a partial page sequence resumes without missing or duplicating shared-game minutes.
- [ ] 2.3 Persist Automatic/12-hour/daily/48-hour policy and last admitted routine attempt, with Automatic as the newly configured default; verify reboot and preference-change tests retain the policy without resetting cloud read/ingest cursors.
- [ ] 2.4 Add a shared admission gate for periodic and play-end opportunities, with network-constrained unique WorkManager jobs; verify coincident events, repeated play ends, failure cooldown, and a recent partial manual read cannot masquerade as completed catch-up.
- [ ] 2.5 Schedule Automatic's daily safety opportunity and overrides' corresponding periodic opportunities, and enqueue an eligible catch-up after observed play ends; verify both paths defer correctly under their shared gate and an offline phone retains normal local behavior.
- [ ] 2.6 Fence/cancel pending routine work on endpoint removal, replacement, or Steam account change, while keeping manual and accuracy-driven placement reads independent of the routine gate; verify no old-account response persists and Steam sync still credits every minute when the endpoint fails.
- [ ] 2.7 Persist a small last-read/outcome/observation-freshness summary separately from the in-memory interval snapshot; verify offline status after process restart distinguishes stale poller observations, last reader success, failure, and partial pagination without exposing credentials.

## 3. History and Settings experience

- [ ] 3.1 Add an unobtrusive session-level cloud mark and separate accessible explanation for recovery, timing, and partial evidence in History; verify unmarked legacy sessions, hidden games, absent reader, font scaling, and day/game expansion remain accurate.
- [ ] 3.2 Add a Cloud activity entry only when the loaded visible History period has contributed sessions, and a pushed detail leading with recovered and cloud-timed sessions linked back to History; verify a new/older loaded window and an empty window show the correct conditional entry.
- [ ] 3.3 Present stored reader attempt/success and observation freshness below contributions, with unknown/partial coverage and failed-read-after-success states; verify offline reopening makes no network request and never describes reader success as live poller health.
- [ ] 3.4 Add the Automatic and bounded cadence choices and routine status once in the cloud Settings destination (Data & privacy after the Settings restructuring lands), preserving Read now; verify copy explains that override limits routine reads only, not minute polling or placement reads, and that next eligibility is not a promised execution time.
- [ ] 3.5 Move new History and Settings labels/counts into Android resources and provide icon-independent accessibility semantics; verify a non-default locale and screen reader can distinguish recovered from timing-informed sessions without relying on color.

## 4. Backup and integration verification

- [ ] 4.1 Version backup session serialization for explicit nullable contribution evidence without exporting endpoint, bearer token, or cursor; verify round-trip import/export preserves kind, partial status, and session minutes.
- [ ] 4.2 Import older supported backups with unknown provenance for new sessions and preserved local provenance for matching sessions, leaving normal merge/recompute intact; verify both legacy and new-format fixtures.
- [ ] 4.3 Run `.\gradlew.bat :gamification:test :app:testDebugUnitTest` and `.\gradlew.bat :app:compileDebugKotlin`; verify all pass and the `CLAUDE.md` UI storage-boundary and haptics checks report no new breach.
- [ ] 4.4 Exercise a configured, offline, stale, failing, and multi-page catch-up with realistic session history on a device; verify displayed contributions match stored evidence, cloud remains optional, and no new routine trigger modifies the poller's server-side schedule.
