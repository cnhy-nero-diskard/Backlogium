# Refine the collections UI

## Why

Custom collections just shipped (add-custom-collections) and real usage surfaced six issues:
the save action is buried below two unbounded lists on the management screen, the screen lags
for players with hundreds of library games because every row composes eagerly, Home collection
cards render clumped together with no visual distinction between modes, and three expected
affordances are missing — a search over the add-games pool, a per-collection accent color, and
a way to mark ordered-queue games as done (today a game without HowLongToBeat data can never
complete a queue).

## What Changes

- **Floating save on the management screen**: Save becomes a floating action pinned to the
  bottom of the screen, reachable at any scroll position; the delete action moves into the
  header row (edit mode only). Save gains an in-flight guard against double taps.
- **Lazy management screen**: the form is restructured into one flat lazy list (fixed sections
  as header items, member rows and addable-game rows as lazy items) so a huge library no longer
  composes hundreds of cards at once — the reported lag. `HomeViewModel`'s per-collection
  derivation is consolidated so N collections no longer create N redundant subscriptions over
  the full library and achievement counts.
- **Add-games search**: the management screen offers a search field filtering the add-games
  pool by game name — the pool only, not current members — with the no-match state rendered
  inside the list so the field can always be cleared.
- **Per-collection accent color**: a collection persists an accent chosen from an expanded set of
  seven Backlogium palette tokens (steel blue, violet, sage, slate, teal, rose, and coral),
  respecting the accent rule that gold stays milestone-reserved and vivid green stays
  live-presence-reserved. The management screen offers the larger picker; Home cards receive a
  low-opacity wash from the chosen accent.
- **Mode-styled Home cards**: each mode gets a distinct card treatment — structured content
  (progress bar for goal modes, countdown for deadline, next-up row for ordered queue) instead
  of one uniform text line — while the member-count surface is kept.
- **Ordered-queue checkmark**: ordered-queue members can be manually marked done. A done member
  stays in the list with its name crossed out and the card greyed; the next-up surface skips
  done members; the queue reads as complete when every member is done or derived-complete.
- **Home card separation and density**: Home collection cards are separated by consistent
  spacing, use a stronger elevated surface with accent color washes, and reduce internal padding
  so they do not read as vertically chunky.

The achievements copy ("x achievements to go") is deliberately unchanged.

## Capabilities

### New Capabilities

None — this refines shipped behavior.

### Modified Capabilities

- `custom-collections`: manual queue-completion state (persistence, next-up skipping, and
  queue-complete semantics) and a persisted per-collection accent color drawn from the app
  palette
- `app-ui`: Home collection cards render separated, mode-styled, and accent-tinted; the
  management screen gains an always-reachable floating save, a header delete action, an
  add-games-pool search filter, an accent picker, and the queue checkmark control

## Impact

- **Affected code:** `ui/collections/CollectionScreen.kt` + `CollectionViewModel.kt` (lazy
  restructure, floating save, header delete, search, accent picker, checkmark),
  `ui/home/HomeScreen.kt` + `HomeViewModel.kt` (card styling/spacing, accent and done
  plumbing), `domain/CollectionSummary.kt` (next-up skip semantics), `data/local`
  (`Collection` and `CollectionMember` entities, `CollectionDao`, `Converters`,
  `BacklogiumDatabase` version bump + additive migration registered in `DatabaseModule`),
  `data/backup` (`BackupFile` DTOs, `BackupExportMapper`, `BackupMergeEngine` — additive
  fields only).
- **No new dependencies, no network calls, no sync changes.** Collections remain app-owned
  state the Steam sync worker never touches.
- **Compatibility:** one additive Room migration (version bump from the collections schema);
  backup files gain optional fields so old backups still restore and new backups read into
  older shapes without loss of existing data.
