## 1. Device spikes (confirm mechanism before implementation)

- [x] 1.1 Bug #1 spike: open the collection management form, focus the Add-games search so the keyboard rises, and
  confirm the blank band appears; record the M3 `Scaffold` `contentWindowInsets` default in use and whether its
  consumption reaches the nested `imePadding()` (double-IME vs. stale-nav-bar). Note whether the save FAB is
  stranded behind the keyboard.
- [x] 1.2 Bug #3 spike: add temporary logging to `onDragEnd` and `onDragCancel` in `CollectionsSection`
  (`HomeScreen.kt`), perform a reorder, close and reopen Home, and record which callback fired and whether the
  `collections.displayOrder` column changed in Room. Determine Candidate A (cancel fires, no persist) vs. Candidate B
  (end fires, persist lands).
- [x] 1.3 Record the spike outcomes in `design.md` Open Questions and pick the concrete fix per bug before starting
  the matching implementation track.

## 2. Keyboard gap fix (bug #1 — CollectionForm IME)

- [x] 2.1 Make the `CollectionForm` scroll container the single IME owner: keep `.imePadding()` on the `LazyColumn`
  and consume the IME inset at the form-container boundary (`Modifier.consumeWindowInsets`) so the shell's
  content-inset contribution is not re-applied.
- [x] 2.2 Move/keep the save `FloatingActionButton` inside the same inset-consumed `Box` so it lifts with the
  keyboard and stays reachable while typing.
- [x] 2.3 Verify on device: no blank band between keyboard and content; save reachable with keyboard raised; content
  adjusts by exactly the keyboard height on raise/lower (no double-reserved space).

## 3. Add-game scroll stability fix (bug #2 — CollectionForm add-game)

- [x] 3.1 Preserve the Add-games search field's focus across an `AddGameRow` tap so adding a game does not dismiss
  the keyboard (e.g. request focus back to the field, or make the add row non-focus-stealing).
- [x] 3.2 Confirm the `LazyColumn`'s stable `appId` keys absorb the member-insertion + addable-removal relayout
  without a scroll reset; if a residual viewport shift remains, pin the Add-games section scroll anchor.
- [x] 3.3 Verify on device: adding an offered game does not jump the form to the top, the offered games stay
  visible, and the search field keeps focus — while preserving "Results visible while typing" and "Adding a
  filtered game preserves filters".

## 4. Reorder persist-on-cancel fix (bug #3 — Home drag-reorder)

- [x] 4.1 In `CollectionsSection`, on `onDragCancel` revert `orderedCards` to the last-known-persisted order (the
  `cards` snapshot) when the drag actually moved (`currentIndex != initialIndex`), so the in-memory presentation
  cannot desync from the DB.
- [x] 4.2 Keep `onDragEnd` persisting via `onReorderCollections` as today; ensure it fires on a clean release.
- [x] 4.3 If the bug-#3 spike found the auto-scroll-during-drag (`HomeScreen.kt:528-540`) causes spurious
  cancellations, harden the gesture so a committed drag ends rather than cancels, without breaking "Drag
  distinguished from scrolling".
- [x] 4.4 Verify on device: a completed drag persists across close/reopen; a cancelled/abandoned drag leaves the
  presented order matching the stored order with no stale in-memory reorder.

## 5. Regression and automated checks

- [x] 5.1 Add or extend Compose UI tests covering the new `app-ui` scenarios: keyboard no-gap, save reachable while
  typing, add-game no-scroll-reset, and reorder-cancel-leaves-order-unchanged.
- [x] 5.2 Add a Home reorder persist test asserting `displayOrder` is written on a completed drag and unchanged on
  a cancelled drag.
- [x] 5.3 Run the existing collections/Home test suite and confirm no regressions in the drag-vs-scroll
  distinction and the add-games search behavior.
- [x] 5.4 Run `openspec validate "fix-collection-ui-behavior"` and `openspec status --change
  "fix-collection-ui-behavior"` to confirm the change is valid and apply-ready.
