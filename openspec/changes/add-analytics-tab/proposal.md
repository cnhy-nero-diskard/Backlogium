# Analytics tab

## Why

Home shows the player's *current* state — level, XP, today's quest, the active streak, now-playing.
History shows *what* happened each day — a day → game → session breakdown. Neither answers the
question that surfaces once a player has been tracked for a few weeks: **what shape does my play
take over time?** Daily totals exist in `daily_progress`; session timestamps exist in `sessions`;
achievement unlocks exist with `unlockedAt`. The information for a trends view is all stored
locally, but no screen joins it into a time-series.

The gap is most visible against streaks. Home reports a single current-streak number and a longest
value; the *history* of streaks — when they started, when they broke, how often the quest is met —
is not surfaced anywhere. The same is true of playtime: History lists per-day totals, but a
month-long view of "how much did I play each day" requires the user to scroll and mentally sum.
Analytics is the screen that answers those questions with a glance.

## What Changes

- **A fifth top-level destination, Analytics**, is added to the bottom navigation bar, peer to
  Home/Library/History/Settings. This is a deliberate change to the four-tab nav contract that
  collections preserved: analytics is a recurring reflective surface, not an occasional deep-dive,
  so it earns a tab slot rather than a pushed sub-destination.
- **A daily playtime bar chart** for the last 30 days, hand-rolled on Compose `Canvas` (no new
  charting dependency). Each bar is one local day's tracked minutes; the quest threshold is drawn
  as a horizontal reference line so met/unmet days read at a glance.
- **A streak summary card** showing current streak, longest streak, and the count of quest-met days
  over the last 30 days — the "how consistent am I" view that Home's single numbers can't carry.
- **A most-played games card** listing the top five games by tracked minutes over the same 30-day
  window, distinct from the Library's lifetime `playtimeForever` ordering.
- **A new `SessionDao` query** for tracked minutes summed per day over a date range, and one for
  tracked minutes summed per game over a date range. Both are read-only projections over existing
  tables — no new persistence, no migration.
- The screen **renders purely from locally stored state**, offline-first like every other screen,
  and presents an empty state before any sessions are recorded.

## Capabilities

### Modified Capabilities
- `app-ui`: the app shell gains a fifth top-level destination (Analytics) and the bottom-nav
  contract expands from four tabs to five; a new Analytics screen requirement covers the daily
  playtime chart, streak summary, and most-played games.

## Impact

- **Affected code (new):** `Destination.ANALYTICS`; `AnalyticsScreen`; `AnalyticsViewModel`;
  `AnalyticsUiState` and its grouping helpers; a `SessionDao` per-day and per-game range query.
- **Affected code (modified):** `Destination.kt` (new enum entry); `BacklogiumAppRoot.kt`
  (NavHost composable + bottom-bar visibility); `SessionDao` (two new `@Query` projections);
  `SessionRepository` (two new flows wrapping them).
- **No new network calls, no new persistence, no migration.** Every input (`sessions`,
  `daily_progress`) is already stored; this is a read-side aggregation like `regroup-history`.
- **No engine change.** Streak/quest authority stays with `DailyProgress` and the gamification
  engine; Analytics' streak summary reads the same persisted values Home reads.
- **No new dependencies.** The chart is drawn on `Canvas`, matching the app's existing hand-rolled
  visuals (the now-playing sheen, the completion progress bars). Adding a charting library would
  break the zero-charting-dependency posture and is explicitly out of scope.
- **Bottom-nav crowding:** five tabs is the Material 3 `NavigationBar` ceiling and remains
  comfortable on the project's `minSdk = 33` target devices; the labels are short ("Home",
  "Library", "History", "Analytics", "Settings").

## Non-goals

- **A range picker.** The first cut is a fixed 30-day window, matching History's initial window.
  A user-chosen range is a later iteration once the value of the trends view is confirmed.
- **All-time trends.** Weeks/months aggregation and all-time sparklines are out of scope for the
  first cut; the 30-day daily chart is the spine.
- **Achievement-over-time charts.** The History screen already surfaces per-day achievement
  thumbnails; a cumulative unlock-rate chart is a separate analytics concern and is not included.
- **Collection progress rollups.** Collections own their own progress surfaces on Home and in the
  collection overview; Analytics does not duplicate them.
- **A "player archetype" summary.** Inferring a completionist-vs-sampler identity is speculative
  and not grounded in a stored signal; it is not included.
- **Exporting or sharing analytics.** Read-only reflection; no CSV/image export.
- **Charts beyond the daily bar chart.** No pie charts, no line charts, no heat-map calendar in
  this change. The bar chart plus the two summary cards is the whole surface.
