# Design — Rarity Standing

## Context

Everything the bound needs is already stored per achievement row:

| Input | Source |
|---|---|
| `N` — total achievements | `AchievementDao.getForGame(appId)` — `GetPlayerAchievements` returns **every** achievement, locked included, so the row count is complete |
| `n` — unlocked count | `Achievement.unlocked` |
| `p[]` — global unlock rates | `Achievement.globalPercent`, refreshed on every merge from `getGlobalAchievementPercentages` — **not** `snapshotPercent` |

`AchievementRepository` fetches on a 1-hour freshness window, so the percentages are fresher than
this feature requires. Two existing behaviors constrain the design:

- **Scope gate.** Achievements are only fetched for games with tracked sessions plus goal/Focus-tagged
  games. An owned-but-unplayed game has no rows, so no standing — consistent with the screen's
  existing empty state.
- **`globalPercent` is nullable.** The global-percentages call is wrapped in `runCatching` and
  degrades to an empty map; `AchievementMerge` then keeps the prior value or leaves null. So a game
  can legitimately have rows with unknown rates.

That second point is the sharp edge in this feature, and the reason for the decision below.

There is a third, sharper one. `Achievement` carries **two** rate columns, and the entity's own KDoc
instructs a reader to prefer the wrong one:

> `[snapshotPercent]` is the global unlock percent captured the first sync that observed the
> achievement unlocked, and is never overwritten afterward — it, **not** the live `[globalPercent]`,
> drives the engine's rarity/XP.

That rule exists so a player's earned XP cannot drift when Steam's rates move, and it is right for
XP. It is wrong here, and the codebase actively points an implementer at it — see the decision below.

## The bound

For a player with `n` of `N` unlocked, `m = N − n`:

```
for k in 1..n:
    S_k    = the (m + k) smallest rates
    bound_k = sum(S_k) / k
ceiling = min over k of bound_k, clamped to 100
```

**Why it holds.** Let `A` be the set of owners with at least `n` unlocked, and `a = |A| / |owners|`.
Any member of `A` is missing at most `m` achievements in total, so within `S_k` (which has `m + k`
members) it is missing at most `m` and therefore **holds at least `k`**. Counting
(owner, achievement) memberships inside `S_k`:

```
sum(S_k) = memberships in S_k / |owners|  ≥  (k · |A|) / |owners|  =  k · a
⇒  a ≤ sum(S_k) / k
```

Every `k` gives a valid bound; the minimum is the tightest. `k ≤ n` keeps `m + k ≤ N`.

**Worked check** (30 of 40, `m = 10`): the 11 rarest summing to 11.2% gives `bound₁ = 11.2%`. The 12
rarest (13.3%) forces two held, so `bound₂ = 6.65%`. The 20 rarest force ten held, and so on — take
the minimum. Matches the pigeonhole reasoning above.

## Goals / Non-Goals

**Goals:**
- A per-game standing derived only from Steam's published rates.
- Every displayed number provable, never merely plausible.
- Graceful, still-valid degradation when inputs are partial.

**Non-Goals:**
- Point estimates, cross-game standing, unplayed-copy correction, external data, a new cache.

## Decisions

- **The math lives in `:gamification` as a standalone `RarityStanding`, not inside `Gamification`.**
  The module's charter is pure, platform-agnostic, JVM-unit-testable logic with no I/O — which this is
  exactly. But `Gamification` is the *rules engine* (XP, levels, quests, streaks), and a statistical
  bound is not a rule: it awards nothing and feeds nothing. Same module, separate object.
  *Why:* free JVM tests over a pure function, and no risk of a display statistic being mistaken for
  an XP input later.

- **`p[]` is live `globalPercent`, never the persisted `snapshotPercent` — the engine's rarity-drift
  rule does not apply to this feature.** The snapshot is deliberately frozen per player at the moment
  an achievement was first seen unlocked, so a library of snapshots is a set of values from many
  different dates, and locked achievements have no snapshot at all. The bound is a statement about
  the owner population *as it stands now*; mixing frozen per-player values into it computes a bound
  against a population that never existed at any single point in time.
  *Why this is called out at all:* the entity KDoc tells a reader to prefer the snapshot, and every
  other rarity consumer in the app obeys it. An implementer doing the locally consistent thing here
  gets a wrong answer with no error and no failing test unless one is written for it (task 1.2).
  Drift is a feature here, not a bug — when Steam's rates move, the bound *should* move with them.

- **When rates are missing, restrict `S_k` to achievements with known rates — never guess.** The
  pigeonhole argument holds for *any* set of size `m + k`, not only the globally rarest, so choosing
  the `m + k` smallest **known** rates yields a bound that is weaker but still proven. Requires at
  least `m + 1` known rates for `k = 1` to exist; below that, the section shows the average only.
  *Why this matters more than it looks:* the tempting shortcut is to drop unknown-rate achievements
  and recompute `N`/`m` from what is left. That silently changes `m` and **invalidates the bound** —
  producing a confidently wrong number in a feature whose entire value is that it cannot be wrong.
  `N` and `m` must always come from the true achievement count.

- **The displayed ceiling is always rounded away from zero.** One decimal below 10%, whole numbers
  above, always upward: `6.65% → 6.7%`, `11.2% → 12%`.
  *Why:* rounding down asserts a tighter bound than was proven. A larger displayed ceiling remains a
  true upper bound, so rounding up is always safe and rounding down is not. *Cost accepted:* an 11.2%
  bound advertises as "top 12% or better", losing a little apparent sharpness in exchange for never
  overstating.

- **A computed ceiling below the display floor renders as `Top 0.1% or better`, never `Top 0%`.**
  Steam truncates very small rates, so a genuinely ultra-rare achievement can report `0.0` and drive
  the bound to zero. Since any larger value is still a valid bound, flooring the *display* at 0.1% is
  both honest and readable — "top 0%" is meaningless and reads as a bug.

- **Hidden entirely when there is nothing to state; average-only when the bound is uninformative.**
  - No achievements, no rows, or the `NO_ACHIEVEMENTS_MARKER` sentinel → section absent.
  - `n = 0` → no `k` exists, so no bound is derivable → average only. (Not covered by the original
    spec; it is a real state for a tagged-but-unplayed game.)
  - `ceiling ≥ 50` → headline hidden, average only. A bound of "at most 60% of owners" excludes
    almost nobody.
  - Fewer than `m + 1` known rates → average only.

- **`average = sum(p[]) / 100` is computed over known rates and labelled as the average owner's
  count.** Steam publishes each `p` as a percentage, so the division converts the sum to an
  achievement count. With unknown rates it understates slightly; that is preferable to omitting the
  one figure that still works when the bound does not.

- **The full-completion ceiling (`rarest = p[0]`) is surfaced only at 100%.** The original spec
  computes it but never renders it. At `n = N` the minimising `k` is 1 and `S₁` is the single rarest
  achievement, so `ceiling` *equals* the full-completion ceiling — the two collapse. Rather than a
  redundant field, a completionist gets the sharper phrasing: at most this share of owners have every
  achievement.

- **The ceiling gets its own formatter; `GameDetailScreen`'s existing `formatPercent` is not reused.**
  That helper is `"%.1f"` — round-to-nearest, which rounds *down* half the time. Correct for an
  achievement's own rarity, fatal for a bound: a displayed value below the proven one is a claim the
  math does not support. Two similarly-named percent formatters on one screen is a small cost against
  silently breaking the feature's only real invariant.

- **The section renders on every surface that hosts `GameDetailList`, including the collection bottom
  sheet.** `GameDetailScreen` is presented both full-screen and inside a sheet from `CollectionScreen`
  (`collection-game-detail-sheet`), sharing one `GameDetailList`. Sharing the section is the default
  and is kept deliberately: suppressing it in the sheet would mean threading a placement flag through
  a screen that has no such concept today, to hide a three-line section.
  *Cost accepted:* the sheet is shorter and narrower, so the section must stay legible there —
  footnote included, since the population caveat is load-bearing honesty rather than decoration and
  is not a candidate for dropping to save a line.

- **Computed in the ViewModel from already-observed rows; nothing is persisted.** `GameDetailViewModel`
  already collects `observeForGame(appId)`; the bound is derived in the same `combine`.
  *Why:* it is a pure function of data already in memory, recomputed for free on any change. Storing
  it would add a column that could go stale against its own inputs.

## Risks / Trade-offs

- **The invalid-`m` trap** — the most likely way to break the math. Called out above and given its
  own task and test.
- **The `snapshotPercent` trap** — the most likely way to break the *inputs*, and more insidious,
  because the entity's KDoc and every other rarity consumer in the app point straight at it. Neither
  trap raises an error; both produce a plausible number. Together they are the reason this feature
  needs the brute-force cross-check in task 2.2 rather than example-based tests alone.
- **"Top 12% or better" underwhelms** — a real cost of honest rounding. The alternative is a number
  that is sometimes unprovable, which for this feature is worse.
- **Steam's population includes unplayed copies**, so bounds are conservative in the player's favour.
  Stated in the footnote rather than corrected.
- **Users may read the ceiling as a percentile.** Mitigated by mandatory "or better" phrasing; worth
  watching in copy review, since a single careless string turns a theorem into a false claim.
- **Interpretation at small `N`** — a 5-achievement game yields loose, jumpy bounds. The `≥ 50%`
  suppression rule handles most of these automatically.

## Migration Plan

None. No new persistence, no schema change, no new network calls, no new cache.

## Open Questions

- Is there value in showing *which* achievements are doing the work (the `m + k` rarest that produced
  the minimising bound)? Interesting, and arguably the most actionable part — "unlock any of these to
  move" — but it invites over-reading a bound as a target, which is the exact misreading the "or
  better" phrasing exists to prevent. **Out of scope for this change**; a candidate follow-up once
  the section has been lived with.

Resolved while finalising:

- *Should the section also appear on the Library row or Home for a standout game?* No — per-game
  detail surfaces only. A bound needs its game's total and the average alongside it to mean anything;
  a bare "top 6.7%" on a library row is the rank-shaped misreading in miniature.
