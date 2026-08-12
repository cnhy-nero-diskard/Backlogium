## Why

Three collections-screen UI bugs degrade the core curation flow on a feature (`improve-collection-organization`,
PR #36) that already has spec contracts for both Home card reordering and the Add-games search. The collection edit
form leaves a blank band between the keyboard and the content when the IME is raised; adding a game from the search
causes a disorienting scroll jump; and dragging collection cards on Home silently fails to persist on gesture
cancellation — so a reorder the user saw land reverts after closing and reopening. All three are user-visible
regressions of behavior the specs already promise.

## What Changes

- Fix the collection edit form (`CollectionForm`) so the raised IME does not leave a visible blank gap between the
  keyboard and the form content, and so the save action stays reachable while typing.
- Fix adding a game from the Add-games search so it does not cause a disorienting scroll reset / viewport jump,
  while preserving the existing "Results visible while typing" contract.
- Fix Home collection-card reordering so a completed drag persists across closing and reopening the screen —
  including the case where the drag gesture is cancelled rather than released — so the in-memory visual reorder
  never desyncs from the persisted order.
- Add device-spike tasks to confirm the exact mechanism of the keyboard-gap (window-insets double-application vs.
  stale nav-bar) and the reorder-revert (onDragEnd vs. onDragCancel) before the implementation settles.

## Capabilities

### New Capabilities

(none — all changes are to existing capabilities)

### Modified Capabilities

- `app-ui`: The collection management form's keyboard/IME behavior and add-game scroll stability become spec'd
  requirements (the IME SHALL NOT leave a blank gap; adding a game SHALL NOT cause a disorienting scroll reset).
  The Home "Reordered collections persist" and "Reorder abandoned" scenarios are sharpened to cover the
  gesture-cancel path, so a cancelled drag neither leaves a stale visual reorder nor persists.
- `custom-collections`: The "Collection display order" requirement is sharpened so a reorder is persisted only on a
  completed drag, and a cancelled or abandoned drag leaves the stored order unchanged AND leaves the in-memory
  presentation consistent with the stored order.

## Impact

- `ui/collections/CollectionScreen.kt` — the `CollectionForm` keyboard-inset handling (`.imePadding()` at ~line 1038)
  and the add-game relayout path; possibly restructure the single tall `LazyColumn` so the Add-games search results
  and the member list do not fight over the keyboard inset.
- `ui/home/HomeScreen.kt` — the drag-reorder gesture handling in `CollectionsSection` (`clearDrag` at ~line 445,
  `onDragEnd`/`onDragCancel` at ~line 514-522), to reconcile in-memory `orderedCards` with the persisted order on
  cancel and ensure `onDragEnd` persists reliably.
- `ui/BacklogiumAppRoot.kt` — possibly the app-level `Scaffold` content insets, if the keyboard-gap root cause is a
  double-applied IME inset at the shell.
- `AndroidManifest.xml` — explicitly select `adjustResize` for the edge-to-edge `MainActivity` so the platform does
  not choose a focus-dependent window adjustment mode on top of Compose's IME inset handling.
- No data-model, API, or dependency changes. Collections remain app-owned Room state; the fix is confined to the
  UI/insets and gesture layers.
