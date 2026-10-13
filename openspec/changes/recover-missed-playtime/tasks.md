## 1. Acceptance fixtures and compatibility audit

- [ ] 1.1 Add failing specification-driven fixtures for Tue 500 -> Wed 570, Mon 500 -> Fri 605, 35 evidence-informed plus 70 residual, repeat reads and no-cloud recovery; verify failures identify the missing behavior before implementation and fixtures assert exact quantities and absent invented timestamps.
- [ ] 1.2 Inventory direct session-quantity consumers and conflicting fallback assertions in `attribute-playtime-from-presence`; verify a checked-in implementation note maps XP, backfill, History, Analytics, Library/detail, collections, recency, backup and historical re-filing to the new canonical inputs without depending on unfinished cloud validation.

## 2. Trustworthy observations and storage migration

- [ ] 2.1 Preserve cumulative-field presence and validate owned/recently-played responses; verify decoding tests distinguish explicit zero from omitted, malformed, negative, private and empty-library responses.
- [ ] 2.2 Add account/game recovery state and daily snapshot persistence with high-water and baseline-epoch semantics; verify tests cover first read, same-day replacement, successful unchanged reads, regressions, missing games and restart recovery.
- [ ] 2.3 Add funded contribution, timed-share, unknown-time placement/recipe and revision/finalization journal records with uniqueness/referential constraints; verify conservation checks reject conflicting quantity graphs and repeated identity insertion cannot add credit.
- [ ] 2.4 Add the next additive Room migration and export its schema from the actual implementation base; verify real per-hop and composed-chain migration tests preserve legacy sessions, daily progress, manual/backfill offsets, hidden/Focus state, rarity snapshots and streak high-water marks.
- [ ] 2.5 Seed matching-account legacy high-water state without inventing historical zones or windows, and integrate reset/account-switch/shared-to-owned conversion; verify missing metadata remains unknown, conversion creates no lifetime delta and inactive-account workers cannot repopulate cleared state.

## 3. Pure attribution planning

- [ ] 3.1 Separate existing authoritative diff quantity from candidate session placement and introduce one pure attribution plan; verify existing high-water quantity tests still pass and no Android/Room/network dependency enters the planner.
- [ ] 3.2 Implement short-gap usability and same/consecutive-date fallback, including stale-open closure and targeted date-known timing; verify tests cover the 30-minute boundary, elapsed-minute rounding guard, midnight continuity, no-evidence Wed 70 and preservation of supplied targeted play instants.
- [ ] 3.3 Implement recovery-zone normalization, frozen date keys and exact earliest-date-first residual allocation; verify 105 -> 27/26/26/26, zero shares, partial/full date coverage, leap dates, DST transitions and timezone changes without later re-bucketing.
- [ ] 3.4 Normalize/deduplicate validated local/cloud intervals, subtract already consumed spans, bound evidence placement and use deterministic largest-remainder rounding; verify 35/70 conservation, partial overlaps, fractional-minute spans, same-game overlapping observers, wrong identity/generation and retained-gap rejection fixtures.
- [ ] 3.5 Implement pending date-unknown quantity for inverted timing and subsequent zero-delta placement; verify no minutes are lost, no future/pre-baseline day is invented, and corrective placement is idempotent after clock recovery.
- [ ] 3.6 Implement compact exact allocation recipes and bounded date expansion for long windows; verify a ten-year gap, a one-minute long gap, overflow guards and paged projections preserve exact quantity without zero activity rows or an arbitrary minute-dropping cap.

## 4. Shared atomic commit path

- [ ] 4.1 Wire periodic and targeted successful reads through the same committer plan, reading recovery baselines inside Room transactions; verify concurrent observers commit the increase once regardless of order and tests disable process-only coordination to exercise database guarantees.
- [ ] 4.2 Commit snapshots, quantity authorization, placement and raw daily credit together, replacing discarded synthetic actions rather than appending another detector's output; verify transaction failpoints roll back all raw state and retries produce no duplicated sessions, buckets or minutes.
- [ ] 4.3 Persist lightweight validated local timing facts/coverage through existing monitoring without authoring owned-game quantity; verify first-sample/end-only evidence does not fabricate continuous intervals, process/network gaps invalidate continuity and no extra Steam polling is scheduled.
- [ ] 4.4 Preserve independent per-game recovery boundaries and reject stale baseline rewinds; verify a targeted read of one game does not shorten another game's gap and an older response exposing new high-water quantity remains conservatively recoverable.
- [ ] 4.5 Close stale sessions at retained trustworthy boundaries while retaining their existing funded shares; verify normal valid short-gap sessions and the one-open-session invariant remain unchanged, and an old open row cannot absorb multi-day fallback minutes.

## 5. Canonical totals, corrections and progression

- [ ] 5.1 Expose domain activity and shared credited-minute/date projections from repository boundaries; verify funded timed shares are not counted alongside contribution quantity, mixed session rows retain their unlinked legacy remainder, and shared sessions retain their existing totals.
- [ ] 5.2 Move XP, Library/detail badges, collection/game totals, smart collections, gap-plan inputs, lifetime-backfill subtraction and computed exports to canonical quantity; verify allocations-only and mixed fixtures reconcile everywhere and historical/manual offsets remain separate.
- [ ] 5.3 Generalize daily credit/correction to canonical affected dates and preserve credit-time hidden/Focus eligibility for new shares; verify quests can flip both directions, unrelated historical days are unchanged, and placement-only correction preserves XP and longest-streak protections.
- [ ] 5.4 Implement journaled resumable long-window materialization and coherent reads of recipe-derived versus materialized dates; verify interruption/retry exposes no partial raw credit, loses no dates and never counts a materialized share twice.
- [ ] 5.5 Finalize single-day current earned credit versus multi-day/past/pending corrective credit with explicit recompute provenance; verify current single-day play can earn one ordinary event while recovered historical credit is silent and an earlier owed event survives.
- [ ] 5.6 Implement mixed corrective/earned derived stages with durable contribution/achievement input IDs, versions and stage completion; verify a newly earned achievement is not swallowed by correction and process-death failpoints around raw commit, corrective persist, earned persist and mark finalization neither consume fresh event eligibility nor duplicate delivered/owed events.
- [ ] 5.7 Keep allocation dates/observation instants out of exact recency, collection session counts and clock-time metrics; verify a recovered bucket increases quantity without fabricating a first/last session instant or session-count increment.

## 6. Delayed evidence and existing cloud integration

- [ ] 6.1 Replace whole-delta cloud placement with bounded evidence share plus local residual, preserving reader-generation fences and coverage rejection; verify no-cloud/read-failure/gapped fixtures still credit every Steam minute and no shared game enters snapshot recovery.
- [ ] 6.2 Add idempotent revision-based late-evidence upgrades for retained contributions after baseline retirement; verify a 105-minute estimate upgraded with 35 minutes of timing stays 105 through duplicate pages, restart and concurrent sync.
- [ ] 6.3 Split/reduce mapped synthetic session shares before replacement, preserving unrelated funded or legacy portions; verify conservation and unchanged manual/protected placements, and unprovable legacy sessions remain untouched.
- [ ] 6.4 Make historical re-filing/apply/reversal aware of contribution revisions and replacement aliases; verify reversal preserves independent later upgrades, imported-balance transfers retain their own confirmation/journals, and no superseded estimate/session is resurrected.

## 7. History, Analytics and accessible explanations

- [ ] 7.1 Include dated allocations in History day/game grouping and paging with unknown-time leaves; verify allocation-only and mixed days show matching totals, no clock boundaries and preserved achievement/quest/expansion behavior.
- [ ] 7.2 Include allocations in Analytics period comparisons, charts, selected-day breakdowns and top games, excluding them from session-shape/time-of-day inputs; verify allocations-only periods show played minutes with timing unavailable rather than measured zero sessions.
- [ ] 7.3 Add localized ! explanation controls to every estimated leaf and relevant day/game/Analytics aggregate; verify residual-window total versus selected-day share, timezone disclosure, accessible names/tap targets, font scaling and accessible chart inspection in Compose tests.
- [ ] 7.4 Present unavailable/pending recovery and pending-date quantity distinctly without claiming invisible real-time observation; verify private/API failure, observed-play-awaiting-Steam and successful unchanged/no-known-play fixtures preserve last-good data and use accurate copy.
- [ ] 7.5 Add deterministic screenshot fixtures for single-day, multi-day, mixed, unavailable and allocations-only insight states; re-record affected narrow/standard Roborazzi baselines and verify them using `docs/visual-regression-screenshots.md`, committing only reviewed expected images with the UI change.

## 8. Backup version 3 and merge safety

- [ ] 8.1 Add mandatory v3 recovery sections and canonical computed rollups while retaining independent session cloud facts; verify a transactional export round trip preserves baseline/epoch, exact quantity, placement revision, frozen dates/zones and uncertainty without exporting credentials or gameplay times for buckets.
- [ ] 8.2 Extend strict full-file preflight/versioned decoding for v1/v2/v3; verify legacy compatibility, rejection of modern fields under legacy markers, missing/null v3 sections, bad zones/dates, overflowing values, conflicting IDs and dangling/rebalanced quantity graphs before writes.
- [ ] 8.3 Implement contribution-aware disjoint-range merge and replacement aliases; verify repeated import, differently partitioned overlapping ranges, older estimates after timed upgrades, protected placement and legacy replacement collisions cannot duplicate quantity or erase known provenance.
- [ ] 8.4 Preserve coherent newer same-account baselines and keep foreign observations inactive under the existing cross-account warning; verify matching restore recovery, legacy fresh-baseline behavior, cross-account import, account switch and an old backup cannot re-enable already credited deltas.
- [ ] 8.5 Include observations, quantity graphs and placement replacements in import's atomic boundary; verify persistence failpoints roll everything back and derived restore recomputes from canonical raw data under local rules.

## 9. Personal Pace observation eligibility

- [ ] 9.1 Feed completed eligible single-day activity into Personal Pace and exclude unresolved multi-day windows from both habits and confidence; verify current/open exclusions, single-day inclusion and no estimate-derived weekday frequency/duration observation.
- [ ] 9.2 Prevent excluded estimated dates from becoming synthesized zeros or covered-date span credit; verify mixed unresolved dates, zero-share gap dates, 14-covered/six-active thresholds and independently resolved dates behave as specified.
- [ ] 9.3 Restore date eligibility only when validated placement fully resolves it; verify late upgrades deterministically change the next offline profile without altering the credited quantity or requiring another network read.

## 10. Integration and acceptance validation

- [ ] 10.1 Run `.\gradlew.bat :gamification:test :app:testDebugUnitTest` and the repository's migration suite; verify all affected quantity, correction, concurrency, backup, account, event and Personal Pace regressions pass and record actual validation limits.
- [ ] 10.2 Run the affected History/Analytics Compose instrumentation tests and accessible explanation checks on an emulator/device; verify no unknown-time clock label appears and accessible ! activation reveals the retained explanation.
- [ ] 10.3 Run `:app:verifyRoborazziDebug` with the documented screenshot test classes, the repository UI storage-boundary check, `git diff --check` and `openspec validate recover-missed-playtime --strict --no-interactive`; verify passing outputs and no unexpected scratch/generated files are staged.
- [ ] 10.4 Perform non-cloud restart/offline acceptance using controlled Steam-authoritative observations for all #181 examples and a separate live recovery when Steam publishes a real increase; verify exact totals, daily quests/streaks, ! persistence, backup round trip and no duplicate credit on retry, distinguishing controlled fixtures from live evidence.
- [ ] 10.5 Verify optional configured-cloud late placement/reversal against deterministic retained evidence without requiring a cloud rollout; record the tested source-precedence behavior and keep unrelated open real-data tasks in `attribute-playtime-from-presence` open unless their own evidence is obtained.
