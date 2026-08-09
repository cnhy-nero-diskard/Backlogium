## Why

Four small gaps across the game-facing surfaces, independent of each other and of the larger changes
in flight.

**Game detail does not link to the game on Steam.** The screen shows art, playtime, HLTB lengths,
achievements, genres, and a live player count, and offers no way to reach the store page any of it
describes. The app already opens external URLs — the onboarding screen uses `LocalUriHandler` for the
Steam API key page — so nothing new is required to do it.

**A History day tile cannot be scanned for what was played.** The day header shows the date, minutes,
Focus minutes, quest state, and a row of achievement thumbnails, but nothing identifying the games —
those appear only after expanding the day. Picking a day out of a scroll therefore means opening days
one at a time. The data is already there: `HistoryDayGroup.games` carries each game's `iconUrl`.

**Collection teaser thumbnails read as achievement icons.** Home's collection cards render member
thumbnails through `GameIcon`, which is fixed at `RoundedCornerShape(8.dp)` — the same rounded-square
treatment achievement icons use. In a small thumbnail strip the two are hard to tell apart.

**Game detail's player count cannot be refreshed on demand.** It polls every 30 seconds while the
screen is open, so a user watching it has no way to ask for a fresh number and no feedback that a
poll is in flight.

## What Changes

- Add a link from the game detail summary to that game's Steam store page, placed directly below the
  summary.
- Show a game's members as thumbnails on each History day tile, so a day can be identified without
  expanding it.
- Render game thumbnails circular in compact rows — Home's collection teasers and History's new day
  thumbnails — distinguishing them from the square achievement icons that appear alongside.
- Add a pull-down gesture on game detail that refreshes the game's current player count, with
  feedback while the refresh is in flight.

**Not in scope:** the pull gesture refreshing anything other than the player count. Achievements,
HowLongToBeat data, and library sync all have their own triggers and their own cadences; Steam sync
in particular is worker-owned and app-wide rather than per-game, so binding it to a per-game gesture
would misrepresent what the gesture does.

Also not in scope: making genre tiles or the new Steam link interactive beyond opening the store
page, and changing what a History day tile shows once expanded.

## Capabilities

### Modified Capabilities

- `app-ui`: `Game summary section` gains the Steam store link.

### New Capabilities

None. Three behaviors are added as new requirements within `app-ui`: compact-row thumbnail shape,
History day-tile game thumbnails, and game detail's manual refresh.

## Impact

**Affected code**

- `ui/gamedetail/GameDetailScreen.kt` — the store link in `GameSummarySection`, and the pull gesture.
- `ui/gamedetail/GameDetailViewModel.kt` — `activePlayers` is a `MutableStateFlow` fed by a
  `while (true) { fetch; delay(30s) }` loop. A manual refresh writes to the same setter; the loop
  should restart so a manual pull is not immediately followed by an already-scheduled poll.
- `ui/history/HistoryScreen.kt`, `ui/history/HistoryGrouping.kt` — the day-tile thumbnail row. The
  grouping already carries per-game `iconUrl`; a cap and overflow count are needed, matching the
  achievement row's existing `HISTORY_ACHIEVEMENT_CAP` treatment.
- `ui/components/GameArt.kt` — `GameIcon` hardcodes `RoundedCornerShape(8.dp)`. It is shared with
  History's game rows, Analytics' most-played list, and game detail, where square is correct — so
  this needs a shape parameter, not a global change.

**Dependency note**

Pull-to-refresh does not exist anywhere in the app today; this introduces the idiom. Material 3 from
the current Compose BOM provides it, so no new dependency is expected.

**Coordination**

`add-display-density-options` also parameterizes `GameIcon`, for size. Whichever lands first should
add parameters without changing the existing defaults so the other is unaffected.

**Risk**

Low. Each item is additive and independently revertable. The one judgement call is visual density:
a History day header would carry two thumbnail rows, which the circular treatment is partly intended
to keep legible.
