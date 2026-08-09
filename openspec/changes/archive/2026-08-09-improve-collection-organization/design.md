# Design — Collection ordering, description, and guarded deletion

## Context

`Collection` stores `id, name, mode, sort, targetDate, accent, timeBasis, createdAt`. There is no
description and no order column. `CollectionDao` lists with:

```kotlin
@Query("SELECT * FROM collections ORDER BY createdAt ASC, id ASC")
```

So Home's order is creation order, permanently.

Member-level ordering already exists and is the precedent to follow: `CollectionMember.orderIndex`,
`CollectionRepository.reorderMembers(collectionId, orderedAppIds)`, and a spec requirement
(`Ordered-queue sequencing`) governing it. This change lifts the same idea to the collection itself.

Deletion runs unguarded from the actions dropdown — `onClick = { showActions = false; viewModel.delete() }` —
and cascades to membership rows. Collections are app-owned state that the sync worker never reads or
writes, so nothing reconstructs a deleted one.

Room is at `version = 12` with hand-written migrations `MIGRATION_1_2` … `MIGRATION_11_12` and no
`autoMigration`.

Home is a plain scrolling column, not a lazy list:

```kotlin
Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { ... }
// collections render as: cards.forEach { CollectionCard(...) }
```

So a drag gesture on a card competes directly with the parent's vertical scroll.

**Sequencing constraint:** `refine-collection-pacing-ui` (41/45 tasks) already modifies
`Collections section on the Home screen` — condensing the card, bounding thumbnails to three, adding
the active-play glow. This change's delta for that requirement is written on top of *that* version,
not the currently-archived one. It must land after that change.

## Goals / Non-Goals

**Goals:**
- Collections orderable by the user, persisted.
- A description that survives, editable in the form and visible on the overview.
- Deletion that cannot happen by accident.
- Preserve the existing order for existing installs on upgrade.

**Non-Goals:**
- Undo for deletion — confirmation is the guard chosen here.
- Collection nesting, grouping, or folders.
- Sorting collections by any derived value (progress, deadline, playtime).
- Rendering the description on the Home card.
- The management form's overall density.

## Decisions

- **A single `MIGRATION_12_13` adds both columns.** Following the hand-written pattern already
  established through twelve versions; no `autoMigration` is introduced for two additive columns.
  *Why:* the file's convention is explicit SQL per version, and the order column needs a data step
  anyway (below), which an auto-migration could not express.

- **The order column is seeded from the existing `createdAt ASC, id ASC` order during migration.**
  Not left at a default and not seeded arbitrarily.
  *Why:* an unseeded order column would silently scramble every existing user's Home screen on the
  upgrade that introduced ordering — a change nobody asked for, appearing as data corruption. The
  spec states this as a requirement rather than leaving it to the migration's discretion.

- **`description` is nullable, and null is distinct from empty.** Never-described stays null;
  cleared-to-empty is stored as such.
  *Why:* it keeps "the user has not written one" separable from "the user deliberately emptied it",
  which matters if the overview ever wants to prompt for a description. This mirrors the codebase's
  standing preference for omitting rather than placeholder-ing an unknown value.

- **Ordering moves off `createdAt` onto the new column, and `reorderCollections` mirrors
  `reorderMembers`.**
  *Why:* the member-reorder mutation already solved the same problem — rewriting a contiguous
  sequence transactionally — one level down. A second, differently-shaped solution for collections
  would be two things to keep correct.

- **The reorder gesture is long-press-then-drag, deliberately not free drag.** A plain drag must
  continue to scroll Home.
  *Why:* the collection cards sit inside `verticalScroll`, and Home is primarily a scrolling surface
  — the collections section is one part of it, below the now-playing panel and the level/quest
  surfaces. If a drag beginning on a card could reorder, Home would become unscrollable wherever
  cards happen to be. The long press is what disambiguates, which is why the spec pins
  "scrolling with a swipe that begins on a card scrolls the screen" as its own scenario.
  *Alternative rejected:* a drag handle on each card — unambiguous, but adds permanent visual weight
  to a card that `refine-collection-pacing-ui` is actively condensing, and the request was explicitly
  a hold-and-drag gesture.
  *Alternative considered:* converting Home to a `LazyColumn` so a list-reorder implementation
  applies cleanly. Rejected for now — Home's structure (full-bleed now-playing panel above an inset
  content column) is deliberate and load-bearing for the header-continuity effect, and restructuring
  it for one gesture is a larger change than the gesture. If the spike shows arbitration is
  unworkable inside `verticalScroll`, this becomes the fallback and should be reconsidered on its
  own merits, not smuggled in.

- **Deletion is guarded by a confirmation that names the collection and states the membership
  consequence.** Not a generic "Are you sure?".
  *Why:* the actions menu is the same control used for "Customize collection", so a mis-tap is
  plausible; and the consequence — losing hand-curated membership that no resync restores — is
  exactly what the user needs told. Naming the collection also guards the case where the wrong
  collection is open.
  *Alternative rejected:* undo via a snackbar — better UX in principle, but it requires either
  deferring the delete or restoring the collection and every membership row, which is real
  transactional work for a rare action. Confirmation is the proportionate guard; undo can be revisited
  if deletion ever becomes routine.

- **The description renders on the overview, never on the Home card.** Stated as a scenario rather
  than left implicit.
  *Why:* `refine-collection-pacing-ui` is condensing that card around name, one status line,
  progress, and thumbnails. A description added there would undo a nearly-finished change. Writing
  it as a negative requirement means a later reader cannot restore it by accident without
  contradicting the spec.

## Risks / Trade-offs

- **Gesture arbitration inside `verticalScroll`.** Long-press-drag nested in a scrolling column can
  produce a card that picks up but cannot be dragged past the viewport edge, or a scroll that
  stutters while the long-press timer runs. → Spike first (task 1.1). The spec's
  "drag distinguished from scrolling" scenario is the pass condition. Fallback is the `LazyColumn`
  restructure, treated as its own decision.

- **Auto-scroll while dragging.** Dragging a card toward the top or bottom of a scrolling screen
  normally scrolls the container. Inside `verticalScroll` this is manual work, not free. → In scope
  for the spike; a reorder that cannot reach off-screen positions is only useful for short lists.

- **Migration seeding must be deterministic.** Seeding by `createdAt` alone leaves ties, and ties
  resolve arbitrarily in SQL without an explicit secondary key. → Seed by `createdAt ASC, id ASC`,
  exactly the existing query's ordering, and test with rows sharing a `createdAt`.

- **This change and `refine-collection-pacing-ui` modify the same requirement.** Applying them out of
  order, or writing this delta against the archived spec, silently reverts that change's card
  condensation. → This delta is already written against their modified version; the ordering
  dependency is stated in the proposal and repeated in tasks.

- **Reordering must not disturb the in-flight glow behavior.** `refine-collection-pacing-ui` adds an
  active-play glow to any card containing the currently played game. A reorder moves cards while that
  glow may be animating. → Verify explicitly; the glow keys off membership, not position, so it should
  follow the card, but it is worth confirming rather than assuming.

## Migration Plan

`MIGRATION_12_13` adds `description TEXT` (nullable) and the display-order column to `collections`,
then seeds the order from `createdAt ASC, id ASC`. No other table changes. Roll back by reverting to
version 12 — but note that a downgrade discards a user's chosen order, since version 12 has nowhere
to hold it.

## Open Questions

- Should the description have a length bound in the UI? Unbounded text is fine to store, but an
  arbitrarily long description would dominate the overview. Presentation detail; a max line count
  with expansion is the likely answer, not a stored limit.
- Should reordering be reachable anywhere other than Home — for instance from the collection
  overview — for accessibility, given a drag gesture is the only path? Worth considering, since
  drag-only reordering is difficult with assistive input. Not spec'd here either way; the existing
  member reorder uses explicit move-up/move-down controls, which is a precedent if an alternative
  path is wanted.
