# Tasks

## Phase 1 — Entry point (independently shippable)

- [x] 1.1 Add an `onOpenGameDetail: (Long) -> Unit` parameter to `CollectionScreen`, defaulted so
      existing call sites and previews stay compilable.
- [x] 1.2 Pass `onOpenGameDetail = { appId -> navController.navigate(gameDetailRoute(appId)) }` into
      `CollectionScreen` in `BacklogiumAppRoot`, mirroring the `LibraryScreen` wiring.
- [x] 1.3 Thread the callback from `CollectionScreen` through `CollectionOverview` to
      `CollectionGameCard`.
- [x] 1.4 Make `CollectionGameCard` clickable, with a content description naming the game so the
      tile is reachable by screen reader.
- [x] 1.5 Confirm the bottom navigation bar still hides correctly on the pushed game detail, and
      that back from game detail returns to the collection overview rather than Home.
- [x] 1.6 Verify the collection overview's scroll position is preserved on return.
- [ ] 1.7 `./gradlew :app:testDebugUnitTest` and a manual pass on a collection with and without
      members.

## Phase 2 — Overlay presentation

### Spike (do first; gates the rest of phase 2)

- [ ] 2.1 Prototype `GameDetailScreen`'s `LazyColumn` inside a partially-expanded `ModalBottomSheet`
      and confirm all three behaviors: the list scrolls internally, a downward drag from the list's
      top dismisses, and a drag on the handle dismisses from any scroll position. If drag and scroll
      cannot be arbitrated without contortion, stop and reassess phase 2 — phase 1 stands alone.

### ViewModel hosting

- [ ] 2.2 Change `GameDetailViewModel` to accept `appId` explicitly, keeping the
      `savedStateHandle["appId"]` read as the fallback for the navigation destination.
- [ ] 2.3 Host the sheet's ViewModel with `hiltViewModel(key = appId.toString())` so two members
      opened in sequence do not share state.
- [ ] 2.4 Confirm the 30-second `currentPlayerCount` polling loop stops when the sheet is dismissed,
      and does not survive on a retained keyed ViewModel.

### Presentation

- [ ] 2.5 Add a presentation parameter to `GameDetailScreen` distinguishing the full destination
      from the overlay.
- [ ] 2.6 In overlay presentation, paint the accent wash within the screen's own bounds and do not
      call `onAccentColorChanged`.
- [ ] 2.7 In full-destination presentation, leave the existing report-upward behavior untouched.
- [ ] 2.8 Replace `BacklogiumAppRoot`'s route-keyed accent-clearing `LaunchedEffect` with one keyed
      on whether a full-destination game detail is presented, so a sheet neither clears its own
      accent nor strands a stale wash on the collection.

### Sheet host

- [ ] 2.9 Host a `ModalBottomSheet` in `CollectionScreen` holding the selected member's `appId` in
      `rememberSaveable`, with `skipPartiallyExpanded = false` so the collection stays visible above.
- [ ] 2.10 Switch phase 1's navigation callback to open the sheet instead, for the collection entry
      point only. The Library entry point keeps navigating to the destination.
- [ ] 2.11 Add a `BackHandler` dismissing the sheet, so system back returns to the collection
      overview rather than closing the collection.
- [ ] 2.12 Confirm the sheet survives a configuration change with the correct member still open.

## Validation

- [ ] 3.1 Walk every scenario in `specs/app-ui/spec.md`: both entry points, both presentations,
      swipe dismissal, back dismissal, scrolling without dismissal, wash containment in the overlay,
      wash spanning the shell on the destination, wash cleared on dismissal, and a game whose header
      art does not resolve.
- [ ] 3.2 Confirm game detail content is identical between the two entry points for the same game.
- [ ] 3.3 Check reduced-motion behavior for the sheet's rise animation against
      `ui/util/ReducedMotion.kt`, consistent with the app's other animated surfaces.
- [ ] 3.4 `./gradlew :gamification:test :app:testDebugUnitTest`.
- [ ] 3.5 `openspec validate collection-game-detail-sheet`.
