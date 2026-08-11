# Tasks — Rarity Standing

> No new network calls, no new persistence, no migration, **no new cache** —
> `AchievementRepository` already gates fetches on a 1-hour window and stores every unlock rate in
> Room, which is tighter than this feature needs.
>
> **Two ways to get this wrong, both silent:**
>
> 1. **Deriving `m` from the achievements that happen to have known rates** instead of from the game's
>    true achievement total. That invalidates the bound and produces a confidently wrong number in a
>    feature whose entire value is that it cannot be wrong. `N` and `m` always come from the full row
>    count.
> 2. **Reading `Achievement.snapshotPercent` instead of `globalPercent`.** The entity's own KDoc says
>    the snapshot — *not* the live percent — drives the engine's rarity and XP. That rule does not
>    apply here and following it breaks the bound: the snapshot is a per-player frozen value from
>    whenever that achievement was first seen unlocked, while the bound is a claim about the owner
>    population *today*. The bound reads live `globalPercent`, always.

## 1. The bound (`:gamification`)
- [x] 1.1 New `RarityStanding` object in `:gamification` — separate from `Gamification`, which is the
  rules engine; this awards nothing and feeds nothing
- [x] 1.2 Input type carrying the game's total achievement count, the player's unlocked count, and the
  known unlock rates — the rates are live `globalPercent` values, never `snapshotPercent`
- [x] 1.3 Implement: sort known rates ascending; for `k = 1..n` take the `m + k` smallest and compute
  `sum / k`; return the minimum, clamped to 100
- [x] 1.4 Missing rates: draw `S_k` only from known rates; keep `m` from the **full** total
- [x] 1.5 Return no bound when `n = 0`, or when fewer than `m + 1` rates are known
- [x] 1.6 Also return the average owner unlock count (known percentage rates normalized by 100)
- [x] 1.7 O(N log N), allocation-light — it runs per composition of the detail screen

## 2. Tests (`:gamification`, JVM)
- [x] 2.1 The worked case: 30 of 40 with the documented rates yields `bound₁ = 11.2%`,
  `bound₂ = 6.65%`, and a minimum matching a hand-computed sweep
- [x] 2.2 Brute-force cross-check on small inputs: enumerate owner-population configurations
  consistent with the rates and assert the true share never exceeds the returned bound — this is the
  test that proves the implementation, not just exercises it
- [x] 2.3 `n = N` → bound equals the rarest rate
- [x] 2.4 `n = 0` → no bound
- [x] 2.5 All rates unknown → no bound; exactly `m + 1` known → a bound is produced
- [x] 2.6 **Partial rates: `m` stays derived from the full total** — a case that would return a
  too-tight (invalid) bound if `m` were recomputed from the known subset
- [x] 2.7 A rate of `0.0` present → no crash, no negative or NaN result
- [x] 2.8 Rates summing above 100 (many common achievements) → clamped to 100
- [x] 2.9 Single-achievement game; two-achievement game (degenerate `N`)

## 3. Presentation rules
- [x] 3.1 Format the ceiling **rounded away from zero**: one decimal below 10%, whole numbers above
  (`6.65% → 6.7%`, `11.2% → 12%`). A **new** formatter — do not reuse `formatPercent` in
  `GameDetailScreen.kt`, which is `"%.1f"` and rounds to nearest, i.e. sometimes *down*, which
  asserts a tighter bound than was proven
- [x] 3.2 A ceiling below the display floor renders as `Top 0.1% or better`, never `Top 0%`
- [x] 3.3 Unit-test the formatter directly, including that no input ever formats to a value **below**
  the true bound
- [x] 3.4 Copy uses ceiling phrasing throughout — "or better", "at most" — and never states a rank or
  exact percentile

## 4. UI
- [x] 4.1 `GameDetailViewModel`: derive the standing inside the existing `combine` over
  `observeForGame(appId)` (`GameDetailViewModel.kt:160`); nothing persisted
- [x] 4.2 Exclude the `NO_ACHIEVEMENTS_MARKER` sentinel from the inputs — **already satisfied**:
  `AchievementDao.observeForGame` filters the sentinel in SQL (`AchievementDao.kt:18`), so a game
  recorded as having no achievements yields an empty list, which is exactly the "omit the section"
  case in 4.6. No work; kept as a documented confirmation
- [x] 4.3 New compact section composable: headline `Top {ceiling}% or better`; inline icon stats
  for `{unlocked}/{total} earned` and `{average} avg`; short caveat stating Steam owners include
  unplayed copies
- [x] 4.4 `ceiling ≥ 50` → hide the headline, show the average only
- [x] 4.5 No bound derivable (`n = 0`, or too few known rates) → show the average only
- [x] 4.6 No achievement data → omit the section entirely
- [x] 4.7 At 100% completion, phrase the ceiling as the share of owners who have completed the game
- [x] 4.8 Place directly below `GameSummarySection` in `GameDetailList`
  (`GameDetailScreen.kt:178`) — `enhance-game-detail` landed 2026-08-04, so the fallback placement
  above the achievement list no longer applies
- [x] 4.9 Verify the section in the collection bottom sheet as well as the full screen.
  `GameDetailList` is shared by both (`CollectionScreen.kt:230`, from `collection-game-detail-sheet`),
  so the section appears in the sheet with no extra wiring — deliberate, not incidental. It must stay
  legible in the sheet's narrower, shorter presentation, footnote included

## 5. Docs & specs
- [x] 5.1 Update `docs/ui-screens-descriptor.md`
- [x] 5.2 Record the pigeonhole argument next to the implementation, so a future reader cannot
  "optimise" the invariant away
- [x] 5.3 Verify the `app-ui` and `rarity-standing` spec deltas match the built behavior
