## 1. Bounded server contract

- [x] 1.1 Add authenticated `mode=range` metadata with oldest retained evidence, frozen-current inputs and server `readAt`; verify FakeFirestore tests for oldest/current-only/empty accounts and zero reads on unauthorized requests.
- [x] 1.2 Add strict historical `from`/`through`/`position` validation and fixed-end 250-transition paging while preserving positionless 31-day reads; verify malformed/future/conflicting inputs use no Firestore reads and default reads stay bounded.
- [x] 1.3 Add first-page predecessor boundary and honest terminal current/window handling without altering raw coverage fields; verify start-boundary, stale-current, fixed-end and pagination tests in `readPresence.test.ts`.
- [x] 1.4 Extend `FakeFirestore` for the required two-sided timestamp predicates and reverse oldest/predecessor queries; verify query tests and `npm run build` plus the functions test suite.

## 2. Durable Android acquisition

- [x] 2.1 Extend Retrofit/DTO and strict response parsing for metadata and historical pages, including range/identity validation; verify repository protocol tests cover old responses, new fields and malformed positions.
- [ ] 2.2 Introduce a Room-backed historical operation, interval stage, boundary, progress, apply/undo journal and identity binding with a schema migration; verify `MigrationTest.kt`-style old-database upgrade and idempotent replay/upsert tests.
- [ ] 2.3 Add independent historical page acquisition to `CloudPresenceRepository`, with the sequence mutex per page, owned-game pending-evidence retention and shared-game ingest before checkpoint commit; verify repository tests for overlaps, failed effects, failed checkpoint, and an unchanged ordinary cursor.
- [ ] 2.4 Implement a 50-page/30-second user-initiated batch that stops with durable progress and explicit continuation, not partial apply or automatic retry; verify a >50-page test completes over multiple invocations and a killed/restarted attempt resumes the same fixed range.
- [ ] 2.5 Fence historical fetch/commit/application by Steam account, reader generation and endpoint identity, and clear staged history on removal, replacement, account change and account Room reset; verify interleaving tests with routine catch-up, placement, verification promotion and invalidation.

## 3. One-time placement, imported-minute transfer and receipt

- [ ] 3.1 Define and test a timezone-aware default rolling-31-day/custom-local-date range and separately confirmed pre-data cutoff instant; verify invalid/ambiguous DST choices, midnight, earliest-day, no-outside-range, and refusal to infer a cutoff from the first session.
- [ ] 3.2 Preserve existing-session date-only placement and make it disjoint from pre-data allocation; verify selected-range clipping, no session overlap, unchanged totals and exact undo for users who decline transfer or never imported Steam history.
- [ ] 3.3 Build a pure per-owned-game pre-data allocation rule using safe full-minute cloud-observed slots before the cutoff, rejecting unknown/gapped/already-counted evidence and capping conversion by the current imported balance; verify deterministic newest-first budget exhaustion, day-boundary splits, per-game caps, and untouched leftover import in unit tests.
- [ ] 3.4 Commit the offset decrease and new dated session rows atomically with the existing re-file mutations and a durable Room apply journal; verify tracked rises exactly as imported falls, unchanged per-game combined minutes/XP/levels, provenance on new History rows, and replay after process death without duplication.
- [ ] 3.5 Persist selected/effective/covered range, cutoff, created IDs, per-game transfer deltas and per-game remaining-import counts in the one-time receipt, keeping completed stage through apply completion; verify zero-change reports, interrupted post-ledger/pre-marker retries and no recompute from later Steam data.
- [ ] 3.6 Reverse original session changes and transfer deltas without erasing later ordinary sessions or later independent game changes; verify Room/DataStore crash windows, idempotent replay, per-game balance restoration and continued use of the legacy re-file backup path.
- [ ] 3.7 Guard the separate Steam-history import reset until an applied cloud transfer is reversed, without changing reset behavior afterward; verify use-case and Settings tests for blocked reset, cloud undo, successful reset and preservation of ordinary tracked play.

## 4. Settings and communication

- [ ] 4.1 Expose authenticated available-bound refresh, lookup failure/empty state, 31-day preset, calendar picker, optional date/time cutoff picker and independent validation in the Settings ViewModel/Compose card; verify ViewModel/UI tests for earliest/current/future/DST dates, missing Steam import and that an unconfigured reader performs no read.
- [ ] 4.2 Show a confirmation with selected/effective start, fixed end, player-asserted cutoff, imported-balance ceiling, uncertainty/coverage caveat, and disclosure that dates/quests/streaks and tracked/imported classification may change but XP/levels/Steam totals will not; verify Settings disclosure tests and no action before confirmation.
- [ ] 4.3 Show selected range/cutoff and page/transition progress during bounded acquisition, explicit Continue on partial/failure, and durable applied/evidenced range, existing-session changes, newly dated minutes and remaining imported balance (including zero changes) afterward; verify Settings feedback across restart and reverse.
- [ ] 4.4 Verify History displays the new dated sessions after Load older, with timing provenance distinct from device-recorded sessions, and game detail, Library/collection totals and XP reflect the reclassified tracked/imported minutes without double count; keep the tracked/0-imported distinction visible when a balance is exhausted, covered by History and game-detail projection tests.
- [ ] 4.5 Refresh stale `README.md` cloud/roadmap text, `docs/architecture-map.md` reader path and `functions/README.md` metadata/history API and cost envelope; verify the docs distinguish re-dating existing sessions from pre-data imported-minute transfer and never claim ordinary reads drain all history.

## 5. Cross-cutting verification

- [ ] 5.1 Run `openspec validate choose-cloud-presence-refile-range --strict`, `npm run build` and functions tests, and relevant Android unit/migration tests; verify a configured user's earliest-retained choice reaches completion in bounded batches, then dates only eligible pre-cutoff imported Steam minutes in History while default first reads, combined credited minutes, reversal and ordinary placement remain unchanged.
