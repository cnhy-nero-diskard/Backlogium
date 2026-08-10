# Design — Game detail from a collection, as an overlay

## Context

`CollectionGameCard` (`ui/collections/CollectionScreen.kt`) renders a member's art, name, playtime,
session count, and trophy counts, and carries no click handler. `CollectionScreen` is wired in
`BacklogiumAppRoot` as `CollectionScreen(onDone = { navController.popBackStack() })` — the Library's
`onOpenGameDetail` equivalent was never passed. So the navigation this change makes into an overlay
does not exist yet; there is no current behavior to preserve or regress.

Game detail is `ROUTE_GAME_DETAIL = "game_detail/{appId}"`, a full NavHost destination. The shell
hides the bottom navigation bar on it (`AnimatedVisibility(visible = !onGameDetail && !onCollectionScreen)`).

Two structural facts constrain the overlay:

1. **`appId` arrives as a navigation argument.** `GameDetailViewModel` reads
   `checkNotNull(savedStateHandle["appId"])`. A composable hosted as sheet *state* has no
   `NavBackStackEntry`, so that read returns null and the screen crashes on open.

2. **The accent wash is shell-owned, not screen-owned.** `GameDetailScreen` computes a muted average
   color from the header art and reports it upward via `onAccentColorChanged`; `BacklogiumAppRoot`
   paints it in `ScreenBackdrop`, behind the Scaffold and the profile header both. The shell clears
   it route-reactively:

   ```kotlin
   LaunchedEffect(onGameDetail, onHome) {
       if (!onGameDetail && !onHome) accentColor = null
   }
   ```

   With a state-hosted sheet the current route is still `ROUTE_COLLECTION`, so `onGameDetail` is
   false. That effect clears the accent the moment the sheet sets it, and the mechanism cannot
   distinguish "game detail is open as a sheet" from "game detail is not open".

Dependency state: `navigationCompose = "2.8.5"`, no `navigation-material`, no accompanist.
`ModalBottomSheet` is available from the Compose BOM and is already used in `CollectionScreen`
for the genre picker.

The archived `2026-08-04-enhance-game-detail/design.md` deliberated presentation only *within* the
screen (header section vs `TabRow` vs summary-as-landing-route). Sheet-versus-destination was never
considered; the full-destination route was inherited from the Library entry point. There is no prior
decision being overturned here.

## Goals / Non-Goals

**Goals:**
- Make collection members openable.
- Keep the collection visible and one downward swipe away while a member's detail is open.
- Keep the Library's presentation of game detail unchanged.
- Keep game detail's content identical across both entry points.

**Non-Goals:**
- Changing game detail's content, achievement list, or sort behavior.
- Changing collection membership, modes, pacing, or overview metrics.
- Making the Library's entry point an overlay.
- Adding a navigation dependency.

## Decisions

- **Ship in two phases, with phase 1 independently useful.** Phase 1 wires
  `onOpenGameDetail` from the shell into `CollectionScreen` and makes `CollectionGameCard`
  clickable, opening the existing full destination. Phase 2 replaces the presentation for that
  entry point only.
  *Why:* the missing-navigation gap and the overlay-presentation question have very different
  risk profiles. Phase 1 is a callback and a `clickable`; phase 2 carries the nested-scroll
  uncertainty. Splitting them means the capability lands even if phase 2 is deferred or reverted.
  *Alternative rejected:* one phase — couples a five-line fix to the change's only real unknown.

- **The sheet is hosted as state inside `CollectionScreen`, with an explicit `BackHandler`.**
  Not as a navigation destination.
  *Why:* navigation is pinned at 2.8.5, which has no Material 3 bottom-sheet destination; that
  arrives with `material-navigation` in the 2.9 line. Adding a navigation dependency and moving
  to a new destination type for one screen is disproportionate to the benefit, and the project's
  standing preference is against dependencies it can avoid.
  *Alternative rejected:* sheet-as-destination — would give back-stack behavior and the `appId`
  navigation argument for free, and should be reconsidered whenever navigation is upgraded for
  other reasons. It is the better long-term shape; it is not worth a dependency bump today.
  *Consequence:* system back must be handled explicitly, which the spec requires as a scenario.

- **`GameDetailViewModel` takes `appId` explicitly, with the `SavedStateHandle` read as its
  fallback.** The sheet obtains its ViewModel via `hiltViewModel(key = appId.toString())` and
  passes `appId` down; the nav destination continues to supply it as a route argument.
  *Why:* this is the minimum change that lets one ViewModel serve both hosts. Keying by `appId`
  also keeps two different games' states from colliding if the sheet is opened, dismissed, and
  reopened on a different member.
  *Alternative rejected:* assisted injection — better typed, but adds a factory and an entry point
  for a single screen. *Alternative rejected:* a second ViewModel for the sheet — guarantees the
  two presentations drift, which is exactly what the spec's "same content regardless of entry
  point" requirement exists to prevent.

- **`GameDetailScreen` gains a presentation parameter, and the wash follows it.** As a full
  destination it reports the color up (unchanged). As an overlay it paints the wash itself, clipped
  to its own bounds, and reports nothing.
  *Why:* the wash is deliberately shell-wide so it can bleed behind the profile header — that is
  the effect it exists for. Inside a sheet the same behavior is a defect: it would tint the
  collection the sheet is supposed to be sitting *on top of*. The two behaviors are genuinely
  different, so the screen has to know which it is.
  *Alternative rejected:* always report up and have the shell decide — the shell would need to know
  the sheet's bounds to clip the gradient, which inverts the ownership and couples the shell to a
  screen's internal layout.

- **Accent clearing keys off game detail being open, not off the route.** The shell's
  `LaunchedEffect` condition is replaced with one driven by whether a full-destination game detail
  is currently presented.
  *Why:* the existing condition is a route predicate and silently produces the wrong answer for a
  screen that can appear without its own route. Left as-is, phase 2 either clears the sheet's
  accent immediately or strands a stale wash on the collection.

- **The overlay's peek height is expressed as leaving the collection visible, not as a fixed dp.**
  Implemented via a partially-expanded `ModalBottomSheet` (`skipPartiallyExpanded = false`) rather
  than a hardcoded fraction.
  *Why:* the requirement is that the collection reads as the context behind the sheet; a fixed
  height would satisfy that on one screen size and not others.

## Risks / Trade-offs

- **Nested scroll versus drag-to-dismiss.** `GameDetailScreen` is a `LazyColumn` and the achievement
  list is long. If the sheet does not own the nested-scroll connection, scrolling up at the top of
  the list dismisses the sheet, or the list swallows the drag and the sheet cannot be dismissed at
  all. → Spike this before building the rest of phase 2 (tasks.md task 2.1). The spec pins the
  intended behavior as a scenario so the spike has a pass condition.

- **One screen, two presentations, held forever.** Every future game detail change must consider
  both, and the spec now describes both. → Accepted deliberately, and bounded: the two forms differ
  only in framing and wash containment, never in content — a constraint the spec states normatively
  so the surfaces cannot quietly diverge.

- **`hiltViewModel(key = ...)` changes ViewModel scoping for the sheet.** A keyed ViewModel is
  retained by the host's `ViewModelStoreOwner`, so repeatedly opening different members accumulates
  ViewModels for the lifetime of the collection screen. → Bounded by collection size and by each
  ViewModel holding only flows and one `Int?`. Worth confirming the player-count polling loop stops
  when the sheet closes, since a retained ViewModel whose `viewModelScope` is still alive would keep
  polling — checked explicitly in tasks.

- **Phase 1 briefly ships a presentation phase 2 replaces.** A user on a phase-1 build gets a full
  screen from collections, then an overlay later. → Acceptable; the destination is the correct
  fallback, not a throwaway, and it remains the Library's behavior regardless.

## Migration Plan

No data migration; no persisted state changes. Phase 1 and phase 2 are independently revertable —
phase 2 reverts to phase 1's full destination, phase 1 reverts to the game tile being inert.

## Open Questions

- Should the overlay expose the same accent-derived treatment on its drag handle or scrim, so the
  transition from the collection's accent color to the game's header color reads as intentional
  rather than as two unrelated palettes meeting at the sheet edge? Deferred to implementation; not
  spec-visible either way.
- If the sheet is open and the user rotates the device, the sheet should survive. `rememberSaveable`
  for the open member's `appId` covers this, but it is worth confirming against the ViewModel keying
  decision above rather than assuming.
