# Grouped playtime history

## Why

History is two flat lists that don't talk to each other. "Recent sessions" is an ungrouped stream of
the last 100 sessions with a game name and a timestamp; "Daily stats" is a separate list of per-day
totals. Neither answers the question the screen exists for — *what did I actually play yesterday, and
when?* To reconstruct a single day you scan a flat list for sessions whose timestamps happen to fall
on that date, then look up that date in a second list to see the total.

The information for a proper breakdown is all there: sessions carry `appId`, `startAt`, `endAt`, and
`minutes`; games carry names and art; days carry totals and quest state. It has simply never been
joined into the day → game → session hierarchy the data naturally forms.

## What Changes

- History becomes a **day-grouped tree**: each day expands into the games played that day, and each
  game expands into its individual sessions.
- Each day header carries what the separate "Daily stats" row shows today — total played, goal
  minutes, quest met — so the two sections **collapse into one structure** instead of duplicating
  each other.
- **Today is expanded by default; past days are collapsed.** At most 30 day-groups load initially,
  with an action to load older ones.
- Game rows show **art and the day's total for that game**; session rows show an **approximate start
  instant and the tracked minutes**, distinguished so the two are not read as the same measurement.
  Deliberately not a start–end range — that shape invites subtracting the two into a duration, which
  can disagree with the tracked minutes once Steam's own counter lags.
- Session reads become **date-ranged** rather than capped at a fixed row count, since 30 days of
  history exceeds the current 100-row limit.
- Each day header also shows a **horizontal row of thumbnails for achievements unlocked that day**,
  joined across every game played, capped at 5 icons with any excess collapsed into a `+N` badge (e.g.
  five icons then `4+`). Days with no unlocks show no row.

## Capabilities

### Modified Capabilities
- `app-ui`: the History screen becomes a day → game → session breakdown with per-day expansion,
  replacing the two independent flat lists.

## Impact

- **Affected code (new):** day/game/session grouping in `HistoryViewModel`; expandable rows and a
  flattened list structure in `HistoryScreen`.
- **Affected code (modified):** `SessionDao` gains a date-ranged observation (the fixed
  `observeRecent(100)` cannot serve 30 days); `SessionRepository`; `HistoryUiState` restructured from
  two flat lists into grouped days; the shared game-art composable extracted out of `LibraryScreen`
  for reuse; `AchievementDao` gains a query for achievements unlocked within a date range, joined
  across games, for the day header's thumbnail row.
- **No new network calls, no new persistence, no migration.** Every input is already stored; this is
  a read-side regrouping.
- **No engine change.** Day totals shown here are presentation sums; `DailyProgress` remains the
  authority for quests and streaks.

## Non-goals

- **Presenting a session's start instant as exact.** `SessionDiffer` documents `startAt` as "the
  previous poll's time (best estimate of when play began)", so it is quantized to the poll cadence
  and must be shown as approximate.
- **Showing a session's end time or deriving its duration from a clock span.** `minutes` comes from
  Steam's cumulative playtime counter and is what XP and day totals are built on; the time between
  poll timestamps is a different measurement that can be larger (a deferred poll, offline play synced
  later) and, shown alongside minutes as a range, reads as an arithmetic error when the two diverge.
  The session row shows only the approximate start plus the tracked minutes, never a range.
- **Splitting midnight-crossing sessions across two days.** Minutes are not distributed evenly across
  a session's span, so splitting would mean prorating — inventing a distribution the data does not
  contain. Sessions group by the day they started.
- **Making History's day totals authoritative.** `DailyProgress` still drives quests and streaks; a
  day header here sums the sessions listed beneath it, which can differ slightly for midnight
  crossers.
- **Filtering or searching history** (by game, by date range).
- **Per-game history on the game detail screen.** That screen's summary shows totals; a per-game
  session list belongs to this screen's concerns.
- **Editing or deleting sessions.**
- **Tapping an achievement thumbnail for detail.** The row is a glance, not a navigation surface;
  achievement detail already lives on the game detail screen.
