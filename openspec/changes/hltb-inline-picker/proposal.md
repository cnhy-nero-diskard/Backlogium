# Inline HowLongToBeat match picker

## Why

Picking a HowLongToBeat match for one game currently takes a detour. From the Library's 3-dot
dialog you tap "Refresh HowLongToBeat", the dialog reports "Needs match review", and you must
dismiss it, find the "Review HLTB matches" link, enter a separate screen, locate the same game
among the others, and choose. Five steps and a screen change to answer a question the dialog had
already framed.

That review screen is the right shape for the *batch* case — after a whole-library sweep, a queue
of games each needing a decision is exactly a list. It is the wrong shape for a single game the
user just asked about, where the decision is immediate and the context is already on screen.

The candidates are also text-only. Choosing between "Prey", "Prey (2006)", and "Prey (2017)" from
names and completion times alone is guesswork; cover art resolves it instantly. HowLongToBeat's
search response includes an image reference that the parser currently discards.

## What Changes

- When a single-game lookup returns ambiguous candidates, the candidates are **selectable inside
  the same dialog** — no navigation, no queue.
- The dialog also offers **changing an already-resolved match**, so a bad auto-match is
  correctable where it is noticed.
- Candidates carry **cover art**, in both the dialog and the batch review screen.
- The batch review screen keeps its role as the **post-sweep queue**, and its entry point is shown
  only when something is actually queued.

## Capabilities

### Modified Capabilities
- `app-ui`: single-game HowLongToBeat match selection happens in place; candidate presentation
  gains cover art; the review screen is scoped to the batch case.
- `hltb-data`: retained candidates carry an image reference.

## Impact

- **Affected code (new):** a candidate picker inside the Library's game dialog.
- **Affected code (modified):** `HltbSearchGame` DTO gains the image field; `HltbCandidate` gains
  `imageUrl`; `HltbBundleParser.mapCandidates` maps it; `LibraryViewModel` exposes candidates and a
  resolve action; `LibraryScreen`'s `GoalDialog`; `HltbReviewScreen`'s candidate rows gain art.
- **No migration.** `HltbCandidate` is persisted as JSON in `HltbData.candidatesJson`, and the new
  field has a default, so old cached candidates deserialize cleanly — they simply have no image
  until re-fetched.
- **No new network calls.** The image reference comes from the search response already being parsed.

## Non-goals

- **Removing the batch review screen.** It remains the queue for post-sweep decisions; only the
  single-game path stops routing through it.
- **A migration or forced re-fetch to populate images** for already-cached candidates. They show a
  placeholder until their next lookup.
- **Free-text HowLongToBeat search** from the dialog (searching a different title than the Steam
  name). A useful escape hatch for badly-named games, but a separate feature.
- **Changing the matcher's thresholds** or its auto-resolve behavior. This changes where a decision
  is made, not how candidates are classified.
