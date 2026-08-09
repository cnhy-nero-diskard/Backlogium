# Design — A real Analytics window

## Context

`AnalyticsViewModel` binds its inputs once, at construction, against a compile-time constant:

```kotlin
private val windowDays: Int = WINDOW_DAYS            // 30
private val inputs = combine(
    sessionRepository.sessionsSince(cutoffMillis()),
    sessionRepository.minutesByGameSince(cutoffMillis()),
    ...
)
```

There is no window state, so nothing downstream can vary. The screen's apparent range control is
local `remember` state that post-filters the already-fetched 30 days:

```kotlin
var chartRange by remember { mutableStateOf(ChartRange.ACTIVE) }
val chartDays = when (chartRange) {
    ChartRange.ACTIVE -> state.dailyMinutes.filter { it.minutes > 0 }
    else -> state.dailyMinutes.takeLast(chartRange.dayCount ?: state.dailyMinutes.size)
}
```

That is why it cannot reach past 30 days, cannot move to an earlier period, and affects no section
but the chart.

The data layer is cutoff-only. Every session query is open-ended:

```kotlin
@Query("SELECT * FROM sessions WHERE startAt >= :cutoff ORDER BY startAt DESC")
@Query("... FROM sessions WHERE startAt >= :cutoff GROUP BY appId")
```

There is no upper bound and no query for the earliest session.

`historyWindowCutoffMillis(windowDays, today, zone)` is shared with the History screen and
deliberately computes the cutoff at a **local day boundary**, not `now - n*24h`, so the oldest day
in a window is whole rather than partial. Any bounded form must apply the same treatment at both
ends or the newest day becomes partial in exactly the way the existing function exists to prevent.

Retention: sessions are never pruned — only `sync_runs` and `presence_decisions` have retention, and
backup snapshots have their own count. But `playtime-backfill` imports pre-existing Steam playtime
as a frozen per-game total, never as dated sessions. So session history begins at install and no
mechanism can extend it backwards.

Not every figure on the screen comes from sessions. `currentStreak` and `longestStreak` are read from
`profileRepository.profile` (player-level counters); `rarityBreakdown` folds
`achievementRepository.unlockedRarityByGame` with no cutoff at all (all-time).

## Goals / Non-Goals

**Goals:**
- One window that actually drives the queries, applied consistently across every session-derived figure.
- Reach earlier periods, bounded by what could possibly have data.
- Always offer a two-week length, for comparability with Steam's own reported figure.
- Make it evident which figures do not follow the window.

**Non-Goals:**
- Changing session detection, recording, or synthesis.
- Persisting the window between visits.
- Reconstructing history before install — impossible, not deferred.
- Windowing the rarity breakdown or the streak counters.
- Arbitrary custom date ranges via a date picker.

## Decisions

- **The window is `(anchor, length)`, resolved to explicit bounds once, and every windowed figure
  derives from those same bounds.**
  *Why:* the reported asks are two different axes — "how much" and "when" — and a length-only design
  cannot express the second. Resolving once and sharing the result is what prevents five sections
  from disagreeing about which period they describe, which is this change's main risk.
  *Alternative rejected:* keeping a length-only window and adding "jump to period" later — retrofitting
  an anchor means touching every query and every section a second time.

- **The window lives on the ViewModel and the input flows re-subscribe when it changes.** The `combine`
  chain becomes window-reactive rather than bound at construction.
  *Why:* it is the only place all the section derivations already meet. Leaving it in the screen
  keeps the post-filter facade that caused this.
  *Consequence:* switching windows re-queries. Acceptable — these are indexed local reads over a
  personal-scale table, and the screen already renders from local state only.

- **`SessionDao` gains upper-bounded equivalents of its cutoff queries, rather than the ViewModel
  filtering a wider fetch in memory.**
  *Why:* an in-memory filter would mean fetching all sessions since the anchor's start to display one
  month, which grows without bound precisely because sessions are never pruned. The bound belongs in
  SQL.
  *Also needed:* a query for the earliest session's timestamp, to bound the anchor. There is no such
  query today; `getAll()` exists but loading every session to find a minimum is the wrong shape.

- **The bounded cutoff helper reuses the local-day-boundary rule at both ends.** Extending
  `historyWindowCutoffMillis` rather than writing a second, subtly different calculation.
  *Why:* that function's docstring exists specifically because the naive `now - n*24h` form produces
  a partial day at the window edge. A bounded window has two such edges, and History and Analytics
  must keep agreeing on what "a day" means.

- **"Active days" becomes a chart display toggle, not a window length.** It is retained, decoupled
  from length, and no longer a member of the range roster.
  *Why:* omitting zero-minute dates is orthogonal to how much time the window covers — it was only
  ever grouped with 7/30 because all three were post-filters over one fetch. As a length it is
  meaningless once the anchor can move.
  *Trade-off:* the current chart defaults to Active days, so keeping that default preserves the
  chart's present appearance while the underlying window default (30 days) preserves the present
  data scope. Neither default changes what the user sees on first open.

- **Streak counters and the rarity breakdown stay outside the window, and the screen says so.**
  *Why:* both are correct as they are — a streak is a present-tense property of the player, and the
  rarity profile is a lifetime achievement, not a period's. Windowing either would be wrong; leaving
  them unlabelled beside windowed figures would be misleading, which is why the spec requires the
  distinction be evident rather than leaving it to layout.
  *Alternative rejected:* windowing `questMetDaysCount` only and hiding the streaks on a moved
  anchor — hiding a figure to avoid explaining it trades one confusion for another.

- **The anchor steps by calendar period for the month and year lengths, and by the selected length
  for the rest.** A 30-day window steps back 30 days; a one-month window steps to the previous
  calendar month; a one-year window steps to the previous calendar year.
  *Why:* the two length families are named differently because people think about them differently.
  "90 days" is a rolling duration and stepping it by a calendar unit would be arbitrary. "Last March"
  and "2025" are calendar objects, and a month-length window that stepped back exactly 30 days would
  drift off the month it is named for within a few steps — after three steps a "month" window
  starting on the 1st is showing the 3rd to the 2nd, which is nobody's idea of a month.
  *Alternative rejected:* stepping everything by the selected length — uniform and simpler, but makes
  the month and year lengths unable to name the periods that justify having them.
  *Alternative rejected:* stepping everything by calendar month — forces 90-day and two-week windows
  into a unit they do not divide.
  *Consequence:* month and year lengths have variable day counts (28–31, 365–366). Every windowed
  figure already derives from resolved bounds rather than a day count, so this costs nothing, but the
  chart's bar count varies between steps and the resolver must not assume a fixed length.

- **Both `30 days` and `1 month` are offered, and the control distinguishes them.** They span nearly
  the same amount of time and step differently — 30 days moves back exactly 30 days, one month moves
  to the previous calendar month.
  *Why:* they answer different questions. "The last 30 days" is a rolling read of recent activity and
  is the window the screen has always shown; "last month" is a calendar object a player wants to look
  back at by name. Offering only the calendar form would remove the rolling read the screen was built
  around; offering only the rolling form would leave "browse previous months" unserved, which is the
  original request.
  *Risk accepted:* two adjacent options that look interchangeable but behave differently is a
  legible-labelling problem, which is why the spec requires the classification be evident rather than
  leaving both as bare chips. If in practice nobody uses one of them, dropping it later is a roster
  change, not a redesign.
  *Default:* 30 days, preserving the screen's present data scope exactly.

- **The anchor is bounded by the earliest tracked session, not by a fixed horizon.**
  *Why:* the true limit is data, and it differs per install. A user three months in and a user three
  years in should both be able to reach exactly as far as they have history. This is also the honest
  answer to "fetch data from previous years" — the control cannot promise a period that no mechanism
  could ever populate.
  *Note:* a period inside available history with no sessions in it is a legitimately empty period and
  stays reachable; only periods entirely before the first session are excluded.

- **The rarity drill-down orders by the same percent that determined each achievement's tier.**
  *Why:* the frozen `snapshotPercent`-versus-live-`globalPercent` distinction is a standing policy
  (`add-steam-achievements` rarity drift, restated in `enhance-game-detail`'s design). Ordering by one
  number and displaying a tier derived from another would let the list read "Legendary" beside a
  percent that could not have produced it — the exact defect game detail's design already guarded
  against.
  *Consequence:* `AchievementRepository.unlockedRarityByGame` exposes percents without achievement
  identity, so it cannot name the rarest twenty. It needs widening, in the same way `GameAchievement`
  was widened for `enhance-game-detail`.

## Risks / Trade-offs

- **Five sections change meaning at once.** A window applied inconsistently misattributes play to the
  wrong period, and would be easy to miss because each section looks plausible alone. → Resolve bounds
  once and pass them; test that every windowed figure agrees for a fixed anchor with sessions placed
  on both boundaries.

- **Boundary-day correctness.** Sessions are attributed to the local date of `startAt`, and a
  midnight-crossing session sits entirely on the day it began (`HistoryGrouping`). A bounded window
  must not split or double-count such a session at either edge. → Explicit tests with sessions
  starting just before and just after both bounds, in a fixed non-UTC zone.

- **Empty periods will be common for new installs.** A user a month in who selects 1 year sees mostly
  emptiness. → Correct and unavoidable; the anchor bound stops it becoming *unbounded* emptiness, and
  the existing empty state already explains the absence.

- **Re-querying on every window change.** → Local indexed reads at personal scale. If it proves
  perceptible, the mitigation is debouncing the control, not returning to a fixed fetch.

- **The rarity drill-down widens a repository projection that was deliberately narrow.**
  `unlockedRarityByGame` exposes only percents, consistent with the drift policy's intent. → Widen it
  to carry identity without exposing the live percent as if it were the snapshot; the policy governs
  which number is authoritative for XP, not whether an achievement may be named.

## Migration Plan

No schema change: the new DAO queries read existing columns, and the earliest-session query is a
`MIN` over `startAt`. No persisted state, no settings key — the window is per-visit. Revertable by
restoring the constant and the screen-local `ChartRange`.

## Open Questions

- Should the quest-met day count sit with the windowed figures visually, given it follows the window
  while the streaks beside it do not? Presentation, but it is the most likely place for the
  windowed/non-windowed distinction to be misread.
