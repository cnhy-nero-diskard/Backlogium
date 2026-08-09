# Tasks

> The four groups below are independent. They can be implemented and merged in any order, and each is
> revertable on its own.

## 1. Steam store link

- [ ] 1.1 Add a link to the game's Steam store page below the game detail summary's content, opening
      it outside the app via `LocalUriHandler`, as `OnboardingScreen` already does.
- [ ] 1.2 Build the URL from the screen's existing `appId` route argument; add no new data source.
- [ ] 1.3 Confirm the link is present for a game with no HowLongToBeat data, no achievements, and no
      player count, since it depends only on the game's identity.
- [ ] 1.4 Confirm returning to the app leaves game detail as it was.
- [ ] 1.5 Give the link an accessible label naming the destination rather than "link".

## 2. Thumbnail shape

- [ ] 2.1 Add a `shape` parameter to `GameIcon` defaulting to its current `RoundedCornerShape(8.dp)`,
      so no existing caller changes appearance.
- [ ] 2.2 Confirm History's game rows, Analytics' most-played list, and the game detail summary are
      visually unchanged.
- [ ] 2.3 Opt Home's collection teaser thumbnails into the circular shape.
- [ ] 2.4 Confirm the themed no-artwork fallback is also circular, not a square behind a circular
      image.
- [ ] 2.5 Leave achievement icons non-circular — their shape now carries meaning, per the spec.
- [ ] 2.6 Coordinate with `add-display-density-options`, which adds a size parameter to the same
      component: add parameters without changing defaults so both changes compose.

## 3. History day thumbnails

- [ ] 3.1 Add a capped game-thumbnail row to the day header, following the achievement row's existing
      capped-with-overflow treatment.
- [ ] 3.2 Order thumbnails by the day's existing game ordering (minutes descending, then name, then
      appId) so the expanded list begins with the games just glanced at.
- [ ] 3.3 Settle the cap from design.md's open questions — the achievement row uses five, but a day's
      game count is typically lower; choose against real data.
- [ ] 3.4 Render the thumbnails circular, per section 2.
- [ ] 3.5 Show no row for a day with recorded progress but no games played.
- [ ] 3.6 Confirm the game row and the achievement row are separately identifiable on a tile carrying
      both, and review the header's overall density with both present.
- [ ] 3.7 Extend the History grouping tests for the cap and overflow count, alongside the existing
      achievement-cap coverage.

## 4. Manual player-count refresh

- [ ] 4.1 Add a pull-down refresh to game detail that re-fetches the current player count.
- [ ] 4.2 Restart the 30-second polling loop on a manual refresh, so a manual pull is not immediately
      followed by an already-scheduled poll.
- [ ] 4.3 Indicate the refresh is in progress and indicate completion.
- [ ] 4.4 Keep the omit-rather-than-placeholder behavior on failure: no player-count line, no error
      state over the summary.
- [ ] 4.5 Confirm the summary's local content and the achievement list stay rendered and usable while
      a refresh is in flight and after a failure.
- [ ] 4.6 Confirm the gesture triggers no library sync, achievement fetch, or HowLongToBeat lookup.
- [ ] 4.7 Verify the gesture against the screen's accent-wash header and its `LazyColumn` scroll.
- [ ] 4.8 ⚠️ If `collection-game-detail-sheet` has landed, decide explicitly whether the pull refresh
      is available in the overlay presentation — a downward drag there is also the sheet's dismiss
      gesture. Safe default is full-destination only. If this change lands first, leave a note for
      that change to make the same decision.

## 5. Validation

- [ ] 5.1 Walk every scenario in `specs/app-ui/spec.md` for the modified and the three added
      requirements.
- [ ] 5.2 Confirm no full-size game icon anywhere changed shape.
- [ ] 5.3 `./gradlew :gamification:test :app:testDebugUnitTest`.
- [ ] 5.4 `openspec validate polish-game-surfaces --strict`.
