# Tasks — Grouped playtime history

> **No migration, no new network calls.** This is a read-side regrouping plus one new DAO query.
>
> **Two things that will bite:** Compose cannot nest a vertically-scrolling `LazyColumn` inside
> another, so the day → game → session tree must be flattened into one lazy list driven by expansion
> state. And `SessionRepository.recentSessions` is capped at 100 rows, which cannot cover 30 days for
> an active player — that is a correctness bug for this screen, not a tuning question.

## 1. Date-ranged session reads
- [x] 1.1 `SessionDao`: add `observeSince(cutoff: Long)` ordered by `startAt` descending
- [x] 1.2 `SessionRepository`: expose a windowed observation (`sessionsSince`). No other caller used
  `recentSessions`, so it and the now-unused `SessionDao.observeRecent` were both retired.
- [x] 1.3 The window is expressed in days and converted to a cutoff at the local day boundary, not
  "now minus N×24h" — otherwise the oldest day in the window is partial

## 2. Grouping in the ViewModel
- [x] 2.1 Restructure `HistoryUiState` from two flat lists into an ordered list of day groups, each
  holding game groups, each holding sessions
- [x] 2.2 Group sessions by the **local date of `startAt`** (midnight crossers belong to the day they
  began)
- [x] 2.3 Within a day, group by `appId`; join names and art from `gameRepository.library`, keeping the
  existing "App {appId}" fallback
- [x] 2.4 Day header total = **sum of that day's sessions**; goal minutes and `questMet` come from
  `DailyProgress`
- [x] 2.5 Sort days descending, games within a day by their day total descending, sessions within a game
  by `startAt` ascending (chronological within the day)
- [x] 2.6 Include days that have `DailyProgress` but no sessions
- [x] 2.7 Window state: 30 day-groups initially, with an action that widens the cutoff
- [x] 2.8 Unit-test the grouping: midnight crosser lands on its start day; day total equals the sum of
  its sessions; a day with progress and no sessions still appears; unknown game falls back

## 3. Day achievement thumbnails
- [x] 3.1 `AchievementDao`: add a query returning unlocked achievements (`appId`, `iconUrl`,
  `unlockedAt`) whose `unlockedAt` falls within a given date range, ordered by `unlockedAt`
- [x] 3.2 `HistoryViewModel`: join that query into each day group by the local date of `unlockedAt`,
  across all games, not just the day's played games (an achievement can unlock retroactively or from
  idle progress)
- [x] 3.3 Cap each day's thumbnail list at 5; if more exist, show 5 icons plus a `+N` badge for the
  remainder
- [x] 3.4 Days with zero unlocked achievements render no thumbnail row
- [x] 3.5 Unit-test: exactly 5 unlocks shows 5 icons and no badge; 6+ shows 5 icons and the correct
  `+N`; 0 unlocks omits the row entirely; unlocks from a game not otherwise played that day still
  appear

## 4. Presentation helpers
- [x] 4.1 `UiFormat`: add a time-of-day formatter (locale-aware, no date part)
- [x] 4.2 `UiFormat`: add an approximate-instant formatter (`approxTime`) for a session's start.
  **Revised after real-user feedback:** the original plan was a start–end range formatter with an
  open-ended form; that shape reads as "subtract these for the duration," which can legitimately
  disagree with the tracked minutes once Steam's counter lags and looked like a miscounting bug on
  first contact with a real user. Dropped the end time entirely rather than better-wording the range.
- [x] 4.3 Unit-test `approxTime`, including formatting across a midnight boundary and confirming the
  output carries no second endpoint to subtract against
- [x] 4.4 Keep the approximation marker and the "played" wording in one place, with a comment on why
  they exist — dropping either makes the screen look arithmetically broken

## 5. Screen
- [x] 5.1 Extract the game-art composable out of `LibraryScreen` (currently private) into
  `ui/components`, preserving its themed loading/error fallbacks
- [x] 5.2 Rebuild `HistoryScreen` as **one flat `LazyColumn`** emitting day headers, then game rows and
  session rows for expanded branches — no nested lazy lists
- [x] 5.3 Expansion state keyed by date and by (date, appId), transient; today expanded by default
- [x] 5.4 Day header: date, total played, goal minutes when non-zero, quest indicator (reuse the
  existing icon treatment from `DayStatRow`), and the achievement thumbnail row from section 3
- [x] 5.5 Game row: art, name, that day's total for the game
- [x] 5.6 Session row: approximate start · tracked minutes, single line (no end time — see 4.2)
- [x] 5.7 Keep the "Daily stats" divider above the past days, with today's group above it
- [x] 5.8 A day with nothing to expand shows no expand affordance
- [x] 5.9 "Load older" at the end of the list; expansion state survives loading more
- [x] 5.10 Preserve the existing unconfigured and empty states verbatim
- [x] 5.11 Day header's goal-minutes copy reuses the existing Focus wording (`HistoryScreen.kt:132`,
  landed via `enhance-library`) rather than reintroducing "goal"
- [x] 5.12 (found in review) A long game title had no `weight`/`maxLines` on the name `Text`, so it
  claimed unbounded width and squeezed the trailing minutes text into an unreadable one-char-per-line
  column. Fixed by weighting the icon+name `Row` and ellipsizing the name.
- [x] 5.13 (found in review) A game's sessions rendered as bare floating `Text` rows below its card,
  reading as unrelated to the game rather than that card's own expanded content. Replaced with a
  single `SessionsPanel` card sharing the game row's horizontal margin and corner radius (flat where
  they meet), so the pair reads as one header + its dropdown content.
- [x] 5.14 (found in review) Tightened card padding (12dp → 10dp) and widened the screen's horizontal
  inset (16dp → 20dp) for slightly shorter, slightly narrower cards, per feedback.

## 6. Verification
- [x] 6.1 Verify expansion state stays attached to the right day when a sync inserts new sessions
- [x] 6.2 Check a day containing an open session: marked as in progress, minutes counted in the total
- [x] 6.3 Check a 30-day window on a heavy library for scroll performance (needs a device/emulator
  run — not verifiable from this environment; no emulator was available) *(verified on device by the
  user — scroll performance acceptable on a 30-day heavy-library window)*
- [x] 6.4 Confirm the quest indicator still reflects `DailyProgress`, not the presented sum
- [x] 6.5 Check a day with 5 achievements (no badge) and a day with 6+ (badge shows correct count)

## 7. Docs & specs
- [x] 7.1 Update `docs/ui-screens-descriptor.md`
- [x] 7.2 Verify the `app-ui` spec delta matches the built behavior
