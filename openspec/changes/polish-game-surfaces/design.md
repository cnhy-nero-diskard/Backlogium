# Design — Game surface polish

## Context

Four independent items. Each is small; the reason for a design note is that three of them touch
shared components or established idioms, and the fourth introduces a new one.

**Steam link.** Precedent exists: `OnboardingScreen` uses `LocalUriHandler` to open the Steam API key
page. `appId` is already the game detail route's argument, so the URL needs no new data.

**History day thumbnails.** `HistoryDayGroup.games` already carries `HistoryGameGroup(appId, name,
iconUrl, minutesPlayed, sessions)`, sorted by minutes descending then name then appId. The day header
already renders a capped icon row for achievements —
`HistoryAchievements(iconUrls, overflowCount)` with `HISTORY_ACHIEVEMENT_CAP = 5` — so the shape of
"capped row with +N" exists to copy rather than invent.

**Thumbnail shape.** `GameIcon` hardcodes `RoundedCornerShape(8.dp)` and is shared by History's game
rows, Analytics' most-played list, game detail's summary, and Home's collection teasers. Only the
last is a compact thumbnail strip.

**Manual refresh.** `activePlayers` is a `MutableStateFlow<Int?>` fed by a loop:

```kotlin
while (true) {
    activePlayers.value = gameRepository.currentPlayerCount(appId)
    delay(ACTIVE_PLAYERS_POLL_INTERVAL_MS)   // 30_000
}
```

It is deliberately outside `content`, which combines only local offline-safe flows, so a slow or
failed network call never holds up the summary or the achievement list. No pull-to-refresh exists
anywhere in the app.

## Goals / Non-Goals

**Goals:**
- Reach a game's store page from its detail screen.
- Identify a History day without expanding it.
- Tell a row of games apart from a row of achievements at a glance.
- Refresh the player count on demand.

**Non-Goals:**
- Refreshing anything but the player count from the pull gesture.
- Changing what a History day shows when expanded.
- Changing full-size game icons anywhere.
- Making genre tiles or the Steam link do anything beyond opening the store page.

## Decisions

- **`GameIcon` gains a `shape` parameter defaulting to its current value; callers opt in.** Not a
  global change.
  *Why:* three of its four call sites are full-size icons where the rounded square is correct.
  Changing the component's default would silently restyle History's game rows, Analytics' most-played
  list, and the game detail summary — surfaces nobody asked to change.
  *Coordination:* `add-display-density-options` needs a size parameter on the same component for grid
  cells. Whichever lands first adds parameters without touching defaults, so the second is unaffected.

- **Circularity is specified as a rule about compact thumbnail rows, not as two separate cosmetic
  tweaks.** One requirement covers Home's teasers and History's new thumbnails together, and states
  that achievement icons stay non-circular.
  *Why:* the two requests are the same request on two surfaces, and the reason both are worth doing is
  the same — a small square game thumbnail is hard to tell from a small square achievement icon.
  Written as two independent tweaks, a future surface would get a third arbitrary answer. Written as a
  rule, shape carries meaning: round is a game, square is an achievement.
  *Consequence:* this makes achievement icons' non-circularity load-bearing rather than incidental, so
  it is stated rather than left as an accident of the current code.

- **The History day tile carries two thumbnail rows, and that is accepted deliberately.** Games and
  achievements each get their own row.
  *Why:* they answer different questions — what did I play, and what did I earn — and merging them into
  one row would make the row unreadable in both directions. The circular treatment is what keeps two
  adjacent small-icon rows legible, which is why these two items belong in one change rather than
  being split.
  *Risk accepted:* the header grows. If it proves too heavy, the cap is the lever to pull, not the
  feature.

- **Thumbnail order follows the day's existing game ordering** (minutes descending, then name, then
  appId), rather than a new ordering for thumbnails.
  *Why:* the spec ties the thumbnails to the expanded list explicitly, so expanding a day never
  reveals a different order from the one just glanced at. Reusing the existing sort makes that free.

- **The pull gesture refreshes the player count and nothing else.**
  *Why:* it is the only thing on the screen that is both live and per-game. Achievements come from the
  sync worker, HowLongToBeat has its own explicit triggers in the Library, and Steam sync is app-wide
  and worker-owned — `profileRepository.syncNow()` is not a per-game operation. A gesture that
  appeared to refresh "the screen" while actually refreshing one line would be a worse lie than a
  gesture with a stated scope. The spec pins the scope as a scenario so it is not widened casually.
  *Alternative rejected:* refreshing achievements too — plausible, but it would make the gesture's
  latency and failure modes depend on the sync path this screen deliberately keeps itself independent
  of.

- **A manual refresh restarts the polling loop rather than running beside it.**
  *Why:* the loop's `delay` is relative to its last fetch, so a manual pull two seconds before a
  scheduled poll produces two fetches in quick succession — the second silently overwriting the first
  with a value the user did not ask for. Restarting makes the manual refresh the new anchor.

- **A failed refresh keeps the omit-rather-than-placeholder behavior, and shows no error.**
  *Why:* the existing contract for this line is explicit — no zero, no dash, no spinner, and the rest
  of the summary renders regardless. A manual gesture is a reason to show progress while it runs, not
  a reason to change what failure looks like or to put an error over locally-derived content.

## Risks / Trade-offs

- **Pull-to-refresh is a new idiom for this app.** Once present on one screen, its absence elsewhere
  becomes noticeable, and a user may expect it to do more than it does. → Bounded by scoping it
  explicitly in the spec. Whether other screens adopt it is a separate decision.

- **The pull gesture competes with the screen's scroll.** Game detail is a `LazyColumn`; pull-to-refresh
  activates on an over-scroll at the top. → Standard, well-supported behavior, but worth verifying
  against the accent-wash header rather than assuming.

- **Interaction with `collection-game-detail-sheet`.** That change may present game detail inside a
  bottom sheet, where a downward pull is also the sheet's dismiss gesture. → Both gestures start with a
  downward drag at the top of the same list. Whichever change lands second must decide explicitly
  whether the pull refresh is available in the overlay presentation or only in the full destination;
  the safe default is full-destination only. Flagged in tasks rather than resolved here, since it
  depends on which lands first.

- **History day header density.** Accepted above; the cap is the lever.

## Migration Plan

No schema, no persisted state, no migration. Each of the four items is independently revertable, and
they can be implemented and merged in any order.

## Open Questions

- How many game thumbnails should a History day tile show before overflowing? The achievement row uses
  five. Matching it is the obvious default, but a day's game count is typically much lower than its
  achievement count, so a smaller cap may read better. Settle against real data.
- Should the Steam link be a text link, an icon button, or a full-width row? Presentation only; the
  spec constrains only its placement below the summary and what it opens.
