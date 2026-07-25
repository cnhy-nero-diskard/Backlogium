# Design — Inline HowLongToBeat match picker

## Context

The pieces for an in-dialog picker already exist and are simply not wired together:

- `HltbRepository.candidatesOf(data)` deserializes retained candidates from `HltbData.candidatesJson`.
- `HltbRepository.resolveMatch(appId, chosen)` persists a choice and clears the candidates.
- `LibraryScreen`'s `GoalDialog` already reads live per-game state (`hltbStatus`, `fetchOp`) and
  already hosts the "Refresh HowLongToBeat" action.

Only `HltbReviewViewModel` calls `candidatesOf` / `resolveMatch` today, so the review screen is the
sole path to a decision.

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

- **The dialog gains a candidate list; both surfaces call the same repository methods.** No new
  persistence path, no duplicated resolve logic — `LibraryViewModel` gets `candidatesOf`/`resolve`
  wrappers mirroring `HltbReviewViewModel`'s.
  *Why:* two entry points to one operation is fine; two implementations of it would drift.

- **Tapping a candidate resolves immediately, with no confirmation step.** The action is cheap,
  visible in its effect (the dialog's status label updates), and reversible via "Change match".
  *Why:* a confirm dialog on top of a dialog to pick from a list is exactly the friction this change
  exists to remove.

- **"Change match" is offered for already-`RESOLVED` games.** Because `resolveMatch` clears
  `candidatesJson`, a resolved game has no retained candidates — so this action must trigger a fresh
  lookup and then present its candidates, rather than reading from cache.
  *Why:* the auto-matcher resolves at ≥0.85 similarity with a 0.15 dominance margin, which will
  occasionally be confidently wrong (sequels, remasters, regional titles). Noticing a wrong
  completion length and being unable to correct it is the worse failure. *Cost:* one network request
  per correction, which is proportionate to a deliberate action.

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

- **Dialog height** — an `AlertDialog` hosting a scrollable candidate list with thumbnails can
  outgrow small screens. Cap the visible candidates and scroll within the dialog; if it fights the
  layout, a bottom sheet is the fallback (a UI-mechanics change, not a behavior change).
- **"Change match" costs a request** — acceptable for a deliberate action, but it must show the
  existing in-flight state (`HltbFetchOp.IN_PROGRESS`) so it does not appear frozen.
- **The dialog is doing more jobs** — it now handles goal tagging, HLTB status, refresh, and match
  selection. If it becomes unwieldy, split the HLTB portion into its own dialog rather than growing
  this one further.
- **Two surfaces resolving matches** — both must leave `fetchedAt` semantics intact; covered by
  routing through `resolveMatch` rather than reimplementing.

## Migration Plan

None. `HltbCandidate.imageUrl` defaults to null, so persisted `candidatesJson` deserializes
unchanged. No schema version bump.

## Open Questions

- Should the dialog show the top N candidates only (say 5) with the review screen as the "see all"
  path? Leaning toward showing all with in-dialog scrolling, since the matcher already retains a
  bounded set.
