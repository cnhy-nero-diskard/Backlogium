# Tasks

> **Sequencing:** this change's `Collection management screen` delta is written against the version
> modified by `improve-collection-organization` (description, delete confirmation). Apply after it and
> do not re-derive the delta from the archived spec. `improve-search-relevance` also edits
> `CollectionForm` — assume the Add-games search has moved when compacting the form.

## 1. Selection spike (gates section 4)

- [x] 1.1 Prototype Library multi-select in a grid cell. Long-press to enter selection, tap to toggle,
      selected state visible, and the goal-management action still reachable — all without reserving
      permanent space in the cell.
- [x] 1.2 Confirm the interaction is identical across list and grid, so density does not change how
      selection is performed.
- [x] 1.3 If no placement works at the densest setting, reconsider the ladder's densest step rather
      than shipping a density where selection is unavailable.

## 2. Density model

- [x] 2.1 Define the density ladder once, shared by both surfaces: identity always; playtime except
      at the densest; completion progress in the list and least dense grid; badges in the list only.
- [x] 2.2 Enforce the strict-subset property — no information appears at a denser setting that was
      absent at a looser one.
- [x] 2.3 Keep the currently-playing signal visible at every density.
- [x] 2.4 Unit-test the ladder: for each density, the expected field set, and the subset relation
      between consecutive densities.

## 3. Persistence

- [x] 3.1 Add two preference keys following the `LIBRARY_FOCUS_SORT` / `LIBRARY_ALL_SORT` pattern —
      one for the Library, one for the collection overview.
- [x] 3.2 Resolve an unrecognized stored value to the default, mirroring `librarySortKeyOrNull`.
- [x] 3.3 Expose them through `SettingsRepository`, not `SettingsDataStore` directly — nothing under
      `ui/` may import a storage type.
- [x] 3.4 Default both to the current rendering, so an upgrade changes nothing until the user chooses.
- [x] 3.5 Confirm the two choices are independent of each other.

## 4. Library

- [x] 4.1 Unify `GoalGameRow` and `BacklogGameRow`'s near-identical bodies before adding densities, so
      the ladder is expressed once rather than per section per density.
- [x] 4.2 Add the density control to the Library.
- [x] 4.3 Render each density per the ladder.
- [x] 4.4 Apply the selection placement from the spike.
- [x] 4.5 Confirm both sections keep their headings and contents across densities, and that adding to
      or removing from the tracked set works at every density.
- [x] 4.6 Confirm progress disappears and returns as density changes, and never appears for a game
      with no HLTB length.

## 5. Collection overview

- [x] 5.1 Add the density control to the overview's member list.
- [x] 5.2 Render members per the same ladder; confirm no member is omitted at any density.
- [x] 5.3 Decide the header-art treatment per density — it is a treatment, not information, so denser
      settings may drop it.
- [x] 5.4 Confirm ordered-queue sequence stays legible and the next game identifiable at every density.
- [x] 5.5 Settle whether the management form's member list follows this choice or keeps a fixed
      rendering, per design.md's open questions.

## 6. Shared components

- [x] 6.1 Parameterize `GameIcon`'s size and shape without changing its defaults, so
      `polish-game-surfaces` can add circular thumbnails independently.
- [x] 6.2 Confirm no existing caller of `GameIcon` changes appearance.

## 7. Form compaction

- [x] 7.1 Compact the configuration controls so the collection's games are reachable without scrolling
      past a full screen of settings on a phone-sized viewport.
- [ ] 7.2 Keep every option available — name, description, mode, order, accent, and for deadline
      collections the target date and estimate basis — directly or behind a disclosure.
- [x] 7.3 Prefer collapsing the seldom-revisited settings over tightening spacing alone.
- [x] 7.4 Confirm the floating save action stays reachable at any scroll position.
- [ ] 7.5 Verify by hand on a phone-sized viewport, which is where the complaint originated.

## 8. Validation

- [x] 8.1 Walk every scenario in `specs/app-ui/spec.md` for all three requirements.
- [x] 8.2 Verify densities are remembered independently and survive leaving and returning.
- [x] 8.3 Verify an unrecognized stored density falls back to the default rather than failing.
- [x] 8.4 Verify density changes nothing about which games appear or their order, under an active
      search and sort.
- [x] 8.5 `./gradlew :gamification:test :app:testDebugUnitTest`.
- [x] 8.6 `openspec validate add-display-density-options --strict`.
