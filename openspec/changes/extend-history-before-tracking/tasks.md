## 1. Evidence and paging

- [ ] 1.1 Expose earliest valid dated unlock after current hidden/retired filtering; verify the floor includes sessions, progress-only dates, and unlocks independently.
- [ ] 1.2 Add explicit recorded/evidence-only day models and date union; verify unlock-only dates inside and before the tracking era show unknown minutes and unavailable quest state.
- [ ] 1.3 Preserve bounded queries and older paging/expansion/reveal behavior; verify sparse empty pages, earliest progress-only rows, midnight transitions, and proven cloud session details.
- [ ] 1.4 Render actual progress-only totals without inventing game/session breakdowns; verify recorded zero differs from unknown and stored quest state remains authoritative.

## 2. XP attribution projection

- [ ] 2.1 Build one coherent contributing-input snapshot matching GamificationUpdater; verify backfill, manual/shared minutes, orphan contributors, hidden games, retired achievements, current HLTB, and safe rules.
- [ ] 2.2 Implement marginal replay through existing engine functions with Long accumulation/Int clamp; verify saturation, rounding, cross-midnight start dates, and the first visible day with prior history.
- [ ] 2.3 Partition dated/undated achievement credit using frozen snapshots; verify null dates/null snapshots and a later null-snapshot repair without claiming historic rarity.
- [ ] 2.4 Reconcile all-date plus undated XP to the same-snapshot engine total; verify randomized mixed ledgers, import/reset/re-file/manual/rule/visibility changes, and stale persisted totals.
- [ ] 2.5 Cache by complete contributing revision and slice for viewport changes; verify a large ledger does not replay on every expansion and work stays off the main thread.

## 3. Presentation and acceptance

- [ ] 3.1 Show daily attributed XP and evidence availability in History; verify achievement-only days and unknown game fallback with accessible localized copy.
- [ ] 3.2 Show today's attributed XP on Home and a reachable undated explanation; verify totals agree with History without changing the Home game-breakdown scope.
- [ ] 3.3 Verify no write, quest/streak change, progress celebration, or new request occurs merely from opening/paging the attribution UI.
- [ ] 3.4 Run relevant domain/repository/UI tests and lint, record/verify affected visual baselines, inspect device states, and run strict OpenSpec validation.
