# Refine the collections UI

## Why

Custom collections just shipped (add-custom-collections) and real usage surfaced seven issues:
the save action is buried below two unbounded lists on the management screen, the screen lags
for players with hundreds of library games because every row composes eagerly, Home collection
cards render clumped together with no visual distinction between modes, and three expected
affordances are missing — a search over the add-games pool, a per-collection accent color, and
a way to mark ordered-queue games as done (today a game without HowLongToBeat data can never
complete a queue). Opening an existing collection also drops the user directly into edit controls,
so the selected games and their useful progress signals are not the primary experience.

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
  of one uniform text line — while the member-count surface is kept. Completion-goal cards show
  aggregate trophy progress as `<unlocked>/<total> trophies · <remaining> left`, or `No trophy
  data` when no member has stored achievement counts.
- **Ordered-queue checkmark**: ordered-queue members can be manually marked done. A done member
  stays in the list with its name crossed out and the card greyed; the next-up surface skips
  done members; the queue reads as complete when every member is done or derived-complete.
- **Home card separation and density**: Home collection cards are separated by consistent
  spacing, use a stronger elevated surface with accent color washes, and reduce internal padding
  so they do not read as vertically chunky.
- **Overview-first collection flow**: opening an existing collection shows a read-only overview
  of its selected games before any customization controls. The overview highlights each member
  with a larger tile and relevant local metrics — playtime, session count, and stored trophy
  progress — while customization, including add games, stays behind a secondary collection-actions
  menu. Creating a collection still opens the setup form directly.

- **Deadline estimate basis and hindsight**: deadline setup offers Main Story, Main + Extra,
  Completionist, or All Styles as the HLTB estimate basis. The collection overview reports the
  time remaining to the deadline, the selected estimate still outstanding after playtime, and
  and, only when the differential is negative, warns about the shortfall, recommends changing the
  deadline, and exposes a direct date-picker shortcut.
- **Home collection game thumbnails**: collection cards show a compact right-aligned row of up to
  five member-game thumbnails; additional members collapse into an `N+` count (for example,
  eleven games render as five thumbnails and `6+`).

Completion-goal trophy copy is explicit and aggregate: unlocked out of total plus the remaining
count, with a no-data fallback rather than implying that missing achievement data means zero.

## Capabilities

### New Capabilities

None — this refines shipped behavior.

### Modified Capabilities

- `custom-collections`: manual queue-completion state (persistence, next-up skipping, and
  queue-complete semantics), a persisted per-collection accent color drawn from the app palette,
  an overview-first read surface with per-member local metrics, and deadline planning based on a
  persisted HLTB completion-length choice, and a compact member thumbnail strip on Home cards
- `app-ui`: Home collection cards render separated, mode-styled, and accent-tinted; the
  management screen gains an always-reachable floating save, a header delete action, an
  add-games-pool search filter, an accent picker, and the queue checkmark control; existing
  collection navigation opens the overview while customization is a secondary action

## Impact

- **Affected code:** `ui/collections/CollectionScreen.kt` + `CollectionViewModel.kt` (overview/editor
  flow, larger member tiles, local metrics, lazy form restructure, floating save, header actions,
  search, accent picker, checkmark), `data/local/dao/SessionDao.kt` + `data/repo/SessionRepository.kt`
  (per-game session counts),
  `ui/home/HomeScreen.kt` + `HomeViewModel.kt` (card styling/spacing, accent and done
  plumbing and deadline fit copy), `domain/CollectionSummary.kt` (next-up skip semantics and
  deadline estimate differential), `data/local`
  (`Collection` and `CollectionMember` entities, `CollectionDao`, `Converters`,
  `BacklogiumDatabase` version bump + additive migrations registered in `DatabaseModule`),
  `data/backup` (`BackupFile` DTOs, `BackupExportMapper`, `BackupMergeEngine` — additive
  fields only).
- **No new dependencies, no network calls, no sync changes.** Collections remain app-owned
  state the Steam sync worker never touches.
- **Compatibility:** additive Room migrations (version bump from the collections schema);
  backup files gain optional fields so old backups still restore and new backups read into
  older shapes without loss of existing data.
