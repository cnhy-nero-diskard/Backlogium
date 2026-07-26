# Tasks — Rarity Standing

> No new network calls, no new persistence, no migration, **no new cache** —
> `AchievementRepository` already gates fetches on a 1-hour window and stores every unlock rate in
> Room, which is tighter than this feature needs.
>
> **The one way to get this wrong:** deriving the player's missing count `m` from the achievements
> that happen to have known rates instead of from the game's true achievement total. That silently
> invalidates the bound and produces a confidently wrong number in a feature whose entire value is
> that it cannot be wrong. `N` and `m` always come from the full row count.

## 1. The bound (`:gamification`)
- [ ] 1.1 New `RarityStanding` object in `:gamification` — separate from `Gamification`, which is the
  rules engine; this awards nothing and feeds nothing
- [ ] 1.2 Input type carrying the game's total achievement count, the player's unlocked count, and the
  known unlock rates
- [ ] 1.3 Implement: sort known rates ascending; for `k = 1..n` take the `m + k` smallest and compute
  `sum / k`; return the minimum, clamped to 100
- [ ] 1.4 Missing rates: draw `S_k` only from known rates; keep `m` from the **full** total
- [ ] 1.5 Return no bound when `n = 0`, or when fewer than `m + 1` rates are known
- [ ] 1.6 Also return the average owner unlock count (sum of known rates)
- [ ] 1.7 O(N log N), allocation-light — it runs per composition of the detail screen

## 2. Tests (`:gamification`, JVM)
- [ ] 2.1 The worked case: 30 of 40 with the documented rates yields `bound₁ = 11.2%`,
  `bound₂ = 6.65%`, and a minimum matching a hand-computed sweep
- [ ] 2.2 Brute-force cross-check on small inputs: enumerate owner-population configurations
  consistent with the rates and assert the true share never exceeds the returned bound — this is the
  test that proves the implementation, not just exercises it
- [ ] 2.3 `n = N` → bound equals the rarest rate
- [ ] 2.4 `n = 0` → no bound
- [ ] 2.5 All rates unknown → no bound; exactly `m + 1` known → a bound is produced
- [ ] 2.6 **Partial rates: `m` stays derived from the full total** — a case that would return a
  too-tight (invalid) bound if `m` were recomputed from the known subset
- [ ] 2.7 A rate of `0.0` present → no crash, no negative or NaN result
- [ ] 2.8 Rates summing above 100 (many common achievements) → clamped to 100
- [ ] 2.9 Single-achievement game; two-achievement game (degenerate `N`)

## 3. Presentation rules
- [ ] 3.1 Format the ceiling **rounded away from zero**: one decimal below 10%, whole numbers above
  (`6.65% → 6.7%`, `11.2% → 12%`)
- [ ] 3.2 A ceiling below the display floor renders as `Top 0.1% or better`, never `Top 0%`
- [ ] 3.3 Unit-test the formatter directly, including that no input ever formats to a value **below**
  the true bound
- [ ] 3.4 Copy uses ceiling phrasing throughout — "or better", "at most" — and never states a rank or
  exact percentile

## 4. UI
- [ ] 4.1 `GameDetailViewModel`: derive the standing inside the existing `combine` over
  `observeForGame(appId)`; nothing persisted
- [ ] 4.2 Exclude the `NO_ACHIEVEMENTS_MARKER` sentinel from the inputs (the DAO's display queries
  already do; confirm whatever query feeds this does too)
- [ ] 4.3 New section composable: headline `Top {ceiling}% or better`; sub
  `You have {n} of {N}. The average owner has {average}.`; footnote
  `Based on all Steam owners, including unplayed copies.`
- [ ] 4.4 `ceiling ≥ 50` → hide the headline, show the average only
- [ ] 4.5 No bound derivable (`n = 0`, or too few known rates) → show the average only
- [ ] 4.6 No achievement data → omit the section entirely
- [ ] 4.7 At 100% completion, phrase the ceiling as the share of owners who have completed the game
- [ ] 4.8 Place beside the game summary if `enhance-game-detail` has landed; otherwise above the
  achievement list

## 5. Docs & specs
- [ ] 5.1 Update `docs/ui-screens-descriptor.md`
- [ ] 5.2 Record the pigeonhole argument next to the implementation, so a future reader cannot
  "optimise" the invariant away
- [ ] 5.3 Verify the `app-ui` and `rarity-standing` spec deltas match the built behavior
