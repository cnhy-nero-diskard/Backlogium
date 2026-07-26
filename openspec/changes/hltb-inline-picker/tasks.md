# Tasks — Inline HowLongToBeat match picker

> No migration and no new network calls. `HltbCandidate` is persisted as JSON with defaults, so a
> new field is backward-compatible on read; the image reference is already in the search response.

## 1. Cover art through the seam
- [ ] 1.1 `HltbSearchGame`: add the image field (`@SerialName("game_image")`, defaulted)
- [ ] 1.2 `HltbCandidate`: add `imageUrl: String? = null`
- [ ] 1.3 `HltbBundleParser.mapCandidates`: compose the absolute image URL here, so the URL
  convention stays with the rest of the endpoint knowledge
- [ ] 1.4 `HltbBundleParserTest`: image mapped when present; null when absent
- [ ] 1.5 Confirm old `candidatesJson` (no image key) still deserializes — add a regression test with
  a stored pre-change payload

## 2. ViewModel wiring
- [ ] 2.1 `LibraryViewModel`: expose retained candidates per game (via `hltbRepository.candidatesOf`)
- [ ] 2.2 `LibraryViewModel.resolveMatch(appId, candidate)` delegating to `hltbRepository.resolveMatch`
  — no reimplementation, so `fetchedAt` preservation is inherited
- [ ] 2.3 "Change match" path: force a fresh lookup, then present the resulting candidates
  (a resolved game has no retained candidates, since resolving clears them)

## 3. Dialog picker
- [ ] 3.1 `GoalDialog`: when status is `NEEDS_REVIEW` and candidates exist, render a selectable
  candidate list with art, name, and completion length
- [ ] 3.2 Tapping a candidate resolves immediately; no confirmation
- [ ] 3.3 "Change match" action for `RESOLVED` games
- [ ] 3.4 Reflect `HltbFetchOp.IN_PROGRESS` and disable selection while a lookup runs
- [ ] 3.5 Scroll the candidate list inside the dialog; verify on a small screen (fall back to a
  bottom sheet only if the dialog genuinely cannot hold it)
- [ ] 3.6 Placeholder art via the existing themed `GameIcon` loading/error pattern

## 4. Review screen
- [ ] 4.1 `CandidateRow`: add cover art, matching the dialog's presentation
- [ ] 4.2 `LibraryScreen`: show the "Review HLTB matches" entry point only when `reviewCount > 0`
- [ ] 4.3 Keep the review route, screen, and empty state as they are

## 5. Docs & specs
- [ ] 5.1 Update `docs/ui-screens-descriptor.md`
- [ ] 5.2 Verify the `app-ui` and `hltb-data` spec deltas match the built behavior
