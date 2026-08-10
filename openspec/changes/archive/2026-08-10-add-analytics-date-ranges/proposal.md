## Why

Analytics answers every question over one fixed window. `AnalyticsViewModel` holds
`const val WINDOW_DAYS = 30` and passes `cutoffMillis()` to every query at construction; there is no
window state anywhere. So the daily chart, top games, session insights, quest-met days, and the
time-of-day pattern all describe the last 30 days and nothing else.

The screen appears to offer a range control, but it does not. `ChartRange` is screen-local
`remember` state that sub-selects from the already-fetched 30 days:

```kotlin
ChartRange.ACTIVE -> state.dailyMinutes.filter { it.minutes > 0 }
else -> state.dailyMinutes.takeLast(chartRange.dayCount ?: state.dailyMinutes.size)
```

It is a view filter over a fixed fetch, applied to the chart alone. Nothing it does reaches the
query, and no other section responds to it. That is why the time-of-day pattern cannot be
range-selected today and why no period before the last 30 days is reachable at all.

Three further gaps follow from the same root: the range roster has no two-week option, so nothing on
the screen lines up with the `playtime_2weeks` figure Steam itself reports; tapping a chart day
reports the day's total but not which games produced it, even though the screen already joins
per-game minutes to the library for its most-played list; and the rarity breakdown reduces every
unlocked achievement to five counters, so the rarest ones cannot be named.

## What Changes

- Promote the window to real state on `AnalyticsViewModel`, expressed as an anchor period and a
  length, driving the queries rather than filtering their results.
- Add a top-level window control offering at least 2 weeks, 30 days, 90 days, and 1 year, and
  allowing the anchor to be moved to earlier periods so previous months and years are reachable.
- **BREAKING (behavioral):** the window applies to every section derived from sessions — daily
  chart, most-played games, session insights, quest-met days, and the time-of-day pattern — not to
  the chart alone. The existing chart-only `Active days / 7 days / 30 days` selector is replaced.
- Retain omitting zero-minute days as a chart display option, decoupled from window length, since it
  is a display choice rather than a range.
- State explicitly which figures do **not** follow the window: the current and longest streak are
  player-level counters, and the achievement-rarity breakdown is all-time. Both would otherwise
  appear to describe a historical period they do not.
- Bound the anchor to the earliest tracked session so periods with no possible data are unreachable
  rather than reachable and blank.
- Extend chart-day inspection to break the selected day down into the games played and each game's
  minutes.
- Make the rarity breakdown expandable into the twenty rarest unlocked achievements, rarest first.

**Not in scope:** any change to how sessions are detected, recorded, or synthesized; new persistence;
persisting the window between visits; and the Analytics screen's visual identity beyond the controls
this change adds.

## Capabilities

### New Capabilities

None. This change modifies existing Analytics behavior.

### Modified Capabilities

- `app-ui`: `Analytics screen` currently specifies "a recent fixed window (the last 30 days)" and a
  chart-only range selector. It gains a screen-level window with a movable anchor, a required
  two-week option, an explicit statement of which figures ignore the window, anchor bounding against
  available history, a per-game breakdown for an inspected day, and an expandable rarity list.

## Impact

**Affected code**

- `ui/analytics/AnalyticsViewModel.kt` — `WINDOW_DAYS` becomes window state; the input flows must
  re-subscribe when it changes rather than being bound once at construction.
- `ui/analytics/AnalyticsScreen.kt` — `ChartRange` is removed in favor of the screen-level control;
  chart-day inspection gains the per-game breakdown; the rarity section gains its expansion.
- `data/local/dao/SessionDao.kt` — every session query is open-ended (`WHERE startAt >= :cutoff`).
  A movable anchor needs upper-bounded equivalents, plus a query for the earliest session to bound
  the anchor.
- `data/repo/SessionRepository.kt` — `sessionsSince` / `minutesByGameSince` gain bounded forms.
- `ui/history/HistoryGrouping.kt` — `historyWindowCutoffMillis` is shared with Analytics and computes
  a cutoff at a local day boundary; the bounded form needs the same day-boundary treatment at both
  ends.
- `data/repo/AchievementRepository.kt` — `unlockedRarityByGame` exposes percents only, with no
  achievement identity, so the rarest twenty cannot currently be named.

**Data availability**

Sessions are never pruned — only diagnostics runs and backup snapshots have retention — so history
depth grows without bound going forward. But `playtime-backfill` imports each game's pre-existing
Steam playtime as a frozen total, not as dated sessions, so **no session exists before install**.
Periods earlier than the first tracked session can never be populated by any means, which is why the
anchor is bounded rather than free.

**Risk**

The largest risk is silent breadth: five sections change meaning at once, and a stale or
inconsistently-applied window would misattribute play to the wrong period. Every windowed figure
must derive from the same resolved bounds.
