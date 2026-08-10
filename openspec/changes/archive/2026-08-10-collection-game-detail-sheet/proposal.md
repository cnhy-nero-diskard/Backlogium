## Why

A game tile in a collection overview is not tappable. `CollectionGameCard` carries no click
affordance and `CollectionScreen` receives only `onDone` — so the overview presents a game's
playtime, sessions, and trophy counts, then offers no way to open the game those numbers describe.
The Library has had that path since it existed; the collection overview simply never got one.

Adding it as a plain push destination would work, but it answers the wrong question. A player
browsing a collection is mid-task — comparing members, deciding what to play next — and a full
screen replacement discards that context and costs a deliberate back press to recover. A partial
overlay that keeps the collection visible behind it, dismissed by swiping down, matches what the
player is actually doing.

## What Changes

Delivered in two phases so the missing capability is not gated on the harder presentation question.

**Phase 1 — the entry point**

- Make a game tile in the collection overview selectable, opening that game's detail.
- Thread `onOpenGameDetail` from the app shell into `CollectionScreen`, mirroring the Library's
  existing wiring.
- Game detail remains the current full push destination. This phase changes reachability only.

**Phase 2 — the overlay presentation**

- Present game detail opened *from a collection* as a partial-height bottom sheet that animates up
  from the bottom, leaving the collection visible above it, and dismissed by swiping down.
- Game detail opened from the Library remains a full destination, unchanged.
- Make the game detail screen's header-art accent wash presentation-aware: reported to the shell
  when it is a full destination (current behavior), painted within its own bounds when it is a
  sheet, so the wash never tints the collection behind it.
- Preserve system-back dismissal for the sheet.

**Not in scope:** changing game detail's content, its achievement list, its sort behavior, or the
Library's presentation of it. Collection membership, modes, and pacing are untouched.

## Capabilities

### New Capabilities

None. This change extends existing UI behavior.

### Modified Capabilities

- `app-ui`: `Game detail screen with achievements` currently specifies the screen as "reachable by
  selecting a game from the Library". It gains the collection overview as a second entry point, and
  gains a presentation requirement distinguishing the full-destination and overlay forms, including
  the accent wash's containment and swipe/back dismissal.

## Impact

**Affected code**

- `ui/BacklogiumAppRoot.kt` — pass `onOpenGameDetail` into `CollectionScreen`; the route-based
  accent-clearing effect (`if (!onGameDetail && !onHome) accentColor = null`) does not survive a
  screen that can appear without a route of its own, and must key off presentation rather than route.
- `ui/collections/CollectionScreen.kt` — `CollectionGameCard` gains a click action;
  `CollectionScreen` gains the callback and, in phase 2, hosts the sheet.
- `ui/gamedetail/GameDetailScreen.kt` — gains a presentation parameter governing whether the accent
  wash is reported up or painted locally.
- `ui/gamedetail/GameDetailViewModel.kt` — `appId` is read as `checkNotNull(savedStateHandle["appId"])`,
  a nav argument. A state-hosted sheet has no `NavBackStackEntry`, so phase 2 must supply `appId`
  by another route or the screen crashes on open. Resolved in design.md.

**Dependencies**

No new dependency is intended. Navigation is pinned at `navigationCompose = "2.8.5"`, which has no
Material 3 bottom-sheet destination — sheet-as-destination would require adding one, and the design
prefers a state-hosted sheet with an explicit back handler instead. `ModalBottomSheet` is already
in use in `CollectionScreen` for the genre picker.

**Risk**

The achievement list is a long `LazyColumn`. Inside a sheet, inner scrolling and drag-to-dismiss
must arbitrate correctly or the sheet dismisses while the user is scrolling. This is the change's
main uncertainty and is called out as a spike in tasks.md.

**Ongoing cost**

One screen gains two presentations, which the spec and every future game detail change must then
hold. This is accepted deliberately: returning to a collection mid-comparison is a different intent
from returning from the Library, and the two-phase split means phase 1 stands on its own if the
overlay proves not to be worth the cost.
