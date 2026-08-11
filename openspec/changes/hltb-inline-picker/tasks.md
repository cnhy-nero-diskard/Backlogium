# Tasks — Inline HowLongToBeat match picker

> No migration and no new network calls *for cover art*. `HltbCandidate` is persisted as JSON with
> defaults, so a new field is backward-compatible on read; the image reference is already in the
> search response. "Change match" does add one deliberate request per correction.

## 1. Cover art through the seam
- [x] 1.1 `HltbSearchGame`: add the image field (`@SerialName("game_image")`, defaulted)
- [x] 1.2 `HltbCandidate`: add `imageUrl: String? = null`
- [x] 1.3 `HltbBundleParser.mapCandidates`: compose the absolute image URL here, so the URL
  convention stays with the rest of the endpoint knowledge
- [x] 1.4 `HltbBundleParserTest`: image mapped when present; null when absent
- [x] 1.5 Confirm old `candidatesJson` (no image key) still deserializes — add a regression test with
  a stored pre-change payload

## 2. A lookup that persists nothing

> `refresh` cannot back "Change match": `classify` is deterministic, so a confidently-resolved game
> re-resolves and `query` writes `candidatesJson = null` — an empty picker, and a silent overwrite of
> any correction the user had already made. See design.md.

- [x] 2.1 `HltbMatcher.scored(query, candidates)`: extract the map-and-sort step `classify` already
  performs, and have `classify` call it — so scoring has one implementation
- [x] 2.2 `HltbRepository.searchCandidates(name): List<HltbCandidate>` — search via the data source,
  return `HltbMatcher.scored(...)` in full. **Writes nothing**: no `upsert`, no `fetchedAt` touch,
  no `candidatesJson`, no status change
- [x] 2.3 Test: `searchCandidates` returns candidates even when the set would auto-resolve
- [x] 2.4 Test: an existing `RESOLVED` row is unchanged after `searchCandidates` — same `hltbId`,
  same `fetchedAt`, same status (this is the regression the whole section exists to prevent)
- [x] 2.5 Test: a failed lookup surfaces as empty/error without touching the stored row

## 3. ViewModel wiring
- [x] 3.1 `LibraryViewModel`: expose retained candidates per game from `hltbRepository.reviewGames`
  keyed by `appId` — **not** `candidatesOf`, which is private and feeds that flow
- [x] 3.2 `LibraryViewModel.resolveMatch(appId, candidate)` delegating to `hltbRepository.resolveMatch`
  — no reimplementation, so `fetchedAt` preservation is inherited
- [x] 3.3 `LibraryViewModel.changeMatch(appId, name)`: call `searchCandidates` and hold the result as
  transient picker state, discarded on dismiss
- [x] 3.4 Represent the in-flight `searchCandidates` call in picker state — it writes nothing, so the
  library row shows no change while it runs and the sheet must carry the progress itself

## 4. Bottom sheet picker
- [x] 4.1 `ModalBottomSheet` hosted by `LibraryScreen` (not nested in `GoalDialog` — separate windows)
- [x] 4.2 Opening the picker dismisses `GoalDialog`
- [x] 4.3 `GoalDialog` entry points: "Choose match" when status is `NEEDS_REVIEW`, "Change match"
  when `RESOLVED`
- [x] 4.4 Candidate rows: cover art, name, completion length — matching the review screen's presentation
- [x] 4.5 Tapping a candidate resolves immediately and dismisses the sheet; no confirmation
- [x] 4.6 The list scrolls internally, so all 20 candidates are reachable without first dragging the
  sheet to full expansion
- [x] 4.7 Reflect the in-flight state from 3.4 and disable selection while a lookup runs
- [x] 4.8 Placeholder art via the existing themed `GameIcon` loading/error pattern

## 5. Review screen
- [x] 5.1 `CandidateRow`: add cover art, matching the picker's presentation
- [x] 5.2 `LibraryScreen`: gate the "Review HLTB matches" **menu item** on `reviewCount > 0` — the
  badge and the count-in-label are already conditional; the row itself is not
- [x] 5.3 Keep the review route, screen, and empty state as they are

## 6. Docs & specs
- [x] 6.1 Update `docs/ui-screens-descriptor.md`
- [x] 6.2 Verify the `app-ui` and `hltb-data` spec deltas match the built behavior
