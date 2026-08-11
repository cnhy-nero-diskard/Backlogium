# Design — Inline HowLongToBeat match picker

## Context

The pieces for an in-dialog picker already exist and are simply not wired together:

- `HltbRepository.reviewGames: Flow<List<HltbReviewGame>>` already emits every `NEEDS_REVIEW` game
  with its candidates deserialized. (`candidatesOf` is *private* and feeds this flow — the flow, not
  the helper, is the seam a second consumer reads.)
- `HltbRepository.resolveMatch(appId, chosen)` persists a choice and clears the candidates.
- `LibraryScreen`'s `GoalDialog` already reads live per-game state (`hltbStatus`, `fetchOp`) and
  already hosts the "Refresh HowLongToBeat" action.

Only `HltbReviewViewModel` reads `reviewGames` / calls `resolveMatch` today, so the review screen is
the sole path to a decision.

One piece genuinely does not exist yet: **a way to obtain candidates without persisting a
classification.** Every current path into the data source runs through `HltbRepository.query`, which
classifies and writes a row. That is fine for a refresh and wrong for "change match" — see the
decision below.

The retained candidate set is **bounded at 20**: `ScrapingHltbDataSource` posts `size = 20`,
`mapCandidates` maps all of them, and `HltbMatcher.classify` retains the full scored list. Bounded,
but far more than a dialog can hold — this sizes the picker.

`HltbCandidate` is `@Serializable` and persisted inside `candidatesJson`. Adding a field with a
default is backward-compatible on read: existing JSON without the key deserializes with the default.
This is what makes the image field free of a migration.

`resolveMatch` deliberately preserves the existing `fetchedAt` when resolving, so resolving does not
reset the freshness window. That behavior must survive being called from a second place.

## Goals / Non-Goals

**Goals:**
- Decide a single game's match without leaving the context that raised the question.
- Make candidates visually distinguishable.
- Keep the review screen meaningful for the batch case.

**Non-Goals:**
- Removing the review screen, free-text search, matcher threshold changes, forced image backfill.

## Decisions

- **The picker is a modal bottom sheet, not a list inside the dialog.** Both surfaces still call the
  same repository methods — no new persistence path, no duplicated resolve logic. `LibraryViewModel`
  reads candidates off the existing `reviewGames` flow (keyed by `appId`) and delegates selection to
  `resolveMatch`, mirroring `HltbReviewViewModel`.
  *Why:* two entry points to one operation is fine; two implementations of it would drift.

- **Opening the picker dismisses the goal dialog; the sheet is hosted by `LibraryScreen`.** A Compose
  `AlertDialog` and a `ModalBottomSheet` occupy separate windows, so nesting the sheet inside the
  dialog fights the framework. The sheet is a complete decision surface on its own, and the resulting
  status is visible on the library row, so there is nothing to return to.
  *Why not keep it in the dialog:* up to 20 candidates with cover art is well past what an
  `AlertDialog` should hold, and the dialog is already carrying goal tagging, HLTB status, and
  refresh. *Trade-off:* the user loses the dialog's other actions on the way to the picker; taking
  the same route back is one tap on the same menu.

- **Tapping a candidate resolves immediately, with no confirmation step.** The action is cheap,
  visible in its effect (the dialog's status label updates), and reversible via "Change match".
  *Why:* a confirm dialog on top of a dialog to pick from a list is exactly the friction this change
  exists to remove.

- **"Change match" is offered for already-`RESOLVED` games, and it must NOT go through `refresh`.**
  Because `resolveMatch` clears `candidatesJson`, a resolved game has no retained candidates, so the
  action needs a fresh lookup. The obvious implementation — call `HltbRepository.refresh` and show
  what comes back — is broken in a way that is worth recording, because it looks correct:

  ```
  refresh(appId, name) → query() → search() → classify()
                                                  │
                                    same name, same algorithm, deterministic
                                                  │
                                                  ▼
                                        Resolved (again)
                                                  │
                                    stores candidatesJson = null
                                                  ▼
                                   picker has nothing to show
  ```

  `HltbMatcher.classify` is a pure function of the Steam name and the search results. A game that
  auto-resolved confidently re-resolves confidently, and `query`'s `Resolved` branch writes
  `candidatesJson = null`. So the picker would be empty for exactly the games it exists to fix.
  Worse, if the user had *already* corrected that game by hand, re-running the auto-matcher
  **overwrites their correction with the wrong auto-match** while showing them nothing to pick from.

  So the repository gains a lookup that classifies and persists nothing:

  ```kotlin
  /** Search + score candidates for a fresh pick. Persists nothing. */
  suspend fun searchCandidates(name: String): List<HltbCandidate>
  ```

  It leaves the stored row untouched — no clobbered match, no `fetchedAt` reset — and returns the
  full scored list regardless of confidence. The user's pick still goes through `resolveMatch`, so
  the `fetchedAt` preservation below is inherited unchanged.
  *Why the action exists at all:* the auto-matcher resolves at ≥0.85 similarity with a 0.15
  dominance margin, which will occasionally be confidently wrong (sequels, remasters, regional
  titles). Noticing a wrong completion length and being unable to correct it is the worse failure.
  *Cost:* one network request per correction, proportionate to a deliberate action.
  *Bonus:* it makes the free-text-search non-goal a later one-argument extension rather than a new
  seam.

- **Images come from the search response's image reference, resolved to a URL in the parser.** HLTB
  returns a filename-style reference; `HltbBundleParser` composes the absolute URL, so the URL
  convention lives in one place with the rest of the endpoint knowledge.
  *Why:* keeps consumers (dialog, review screen) dealing in plain URLs, and keeps a scraped
  convention behind the seam that already owns scraped conventions.

- **Stale cached candidates render a placeholder, not a gap.** Candidates persisted before this
  change deserialize with `imageUrl = null`. Reuse the existing themed `GameIcon` loading/error
  fallback pattern so a missing image looks intentional.
  *Why:* the alternative — a migration or a forced re-sweep to backfill art — is a throttled
  multi-minute network sweep for decoration.

- **The review screen's entry point is hidden when the queue is empty.** Today the Library always
  shows "Review HLTB matches", reading as a permanent destination. With single-game decisions handled
  in place, its remaining purpose is the post-sweep queue, so it should appear only when
  `reviewCount > 0`.
  *Why:* a link to a screen that always says "Nothing to review" trains the user to ignore it.
  *Kept deliberately:* the screen itself, its route, and its behavior — a 300-game sweep can queue
  dozens of decisions, and a list is the right shape for that.

## Risks / Trade-offs

- **Sheet height** — settled by using a bottom sheet, which scrolls to full height natively. The
  list must still scroll internally rather than relying on the sheet's drag, so a 20-candidate list
  is reachable without dragging the sheet to full expansion first.
- **"Change match" costs a request** — acceptable for a deliberate action, but it must show the
  existing in-flight state (`HltbFetchOp.IN_PROGRESS`) so it does not appear frozen. Note the
  in-flight state now covers a `searchCandidates` call that writes nothing, so nothing in the
  library row changes while it runs; the sheet itself has to carry the progress affordance.
- **A non-persisting lookup is a new shape for this repository** — every other path writes what it
  fetched. The candidates behind the sheet are therefore transient view state, discarded on dismiss.
  If the user backgrounds the app mid-pick, they re-tap "Change match" and pay the request again.
  Acceptable; persisting them would mean inventing a "candidates without a classification" row state.
- **Two surfaces resolving matches** — both must leave `fetchedAt` semantics intact; covered by
  routing through `resolveMatch` rather than reimplementing.

## Migration Plan

None. `HltbCandidate.imageUrl` defaults to null, so persisted `candidatesJson` deserializes
unchanged. No schema version bump.

## Resolved Questions

- **Show the top N candidates only, with the review screen as the "see all" path?** No — show all,
  scrolling inside the bottom sheet. Two things settled this. First, the retained set is bounded at
  20, not the handful the question assumed, so a sheet rather than a dialog is the right container
  and a cap buys little once scrolling exists. Second, the review screen **cannot** serve as the
  "see all" fallback: a `RESOLVED` game reached via "change match" is not in the review queue, so
  there is no screen to overflow to. Any cap would have to expand in place, which is a mode for no
  gain — scored ordering already puts the likely answer in the first few rows.
