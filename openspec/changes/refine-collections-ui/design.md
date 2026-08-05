# Design — refine-collections-ui

## Context

Custom collections just shipped (add-custom-collections, schema v9). The management screen
(`ui/collections/CollectionScreen.kt`) is one `verticalScroll` `Column` that eagerly composes
the whole form — including **every member row and every addable library-game row** — and
re-composes all of it on any state change. Home renders one card per collection from
`HomeViewModel`, which opens **one flow per collection**, each combining that collection's
members with the **entire** `gameRepository.library` and `achievementRepository.counts`.
Banner values derive purely in `domain/CollectionSummary`. Collections and memberships are
Room tables (`collections`, `collection_members`) carried by backup/restore.

Reported pain: save sits dead last below two unbounded lists; the screen lags with hundreds
of library games; Home cards touch edge-to-edge and are all visually identical; no search
over the add pool; ordered queues cannot be advanced manually (a game without HowLongToBeat
data can never be "complete", so `queueCompleted` is unreachable for most libraries); and tapping
an existing collection lands in a dense edit form instead of showing the collection itself.

Constraints: offline-first (no network anywhere in this feature), the documented palette's
semantic rules (gold = milestones only, vivid green = live presence only), and the buffered
edit model of the management screen (cancel discards, save persists atomically).

## Goals / Non-Goals

**Goals:**
- Save reachable at any scroll position; delete relocated; double-tap-safe saving.
- Management screen stays responsive with hundreds of library games.
- Home cards separated, mode-styled (structured surfaces), and tinted by a user-chosen accent
  drawn only from Backlogium palette tokens.
- Existing collections open to a read-only overview that foregrounds selected games and collection
  metrics; creation remains a direct setup form and customization is a secondary action.
- Overview member tiles are larger and show playtime, session count, and stored trophy progress
  for each selected game.
- Search over the add-games pool; an ordered-queue checkmark that persists and advances the
  next-game surface.
- Backup/restore keeps working across old and new file shapes.

**Non-Goals:**
- Changing banner arithmetic beyond the done-mark semantics; completion-goal cards now expose
  aggregate unlocked/total trophies and the remaining count when achievement data exists.
- Drag-and-drop reordering, per-member deadlines, user colors outside the palette.
- Any sync/network change — collections stay untouched by the Steam sync worker.

## Decisions

1. **One flat `LazyColumn` for the management screen** — fixed sections (name, mode, order,
   target date, accent, section headers, search field) as `item {}` blocks; members and
   addable games as `items(...)` keyed by `appId`. This is the regroup-history precedent
   (Compose forbids nested vertical lazy lists; flatten instead). *Alternative rejected:*
   pagination/"show more" — hides games rather than fixing composition cost.

2. **Save = floating action button overlaid on the screen** (`Box`, FAB aligned bottom-end),
   **delete = header `IconButton`** (edit mode only, opposite the back arrow). The feedback
   asked for a floating button; a sticky footer was the alternative but occupies permanent
   vertical space and reads as less of an answer to the ask. M3's FAB has no `enabled`
   parameter, so the blank-name state is expressed by dimming the container and guarding the
   click; a `saving` flag in the ViewModel rejects re-entrant taps (today two quick taps on a
   new collection would run `create()` twice before `done` pops the screen). The list ends
   with a spacer sized for FAB clearance so no row hides under it.

3. **Search is composable-local state** filtering `addableGames` by case-insensitive name
   containment — pool only, members are never filtered. Keeping the query out of the ViewModel
   means keystrokes re-compose only the list section, not the whole state graph. The no-match
   state renders **inside** the list beneath the field (the enhance-library lesson: otherwise
   an unmatched query unmounts the field and can't be cleared).

4. **Accent = `CollectionAccent` enum stored as nullable TEXT** (`STEEL_BLUE`, `VIOLET`,
   `SAGE`, `SLATE`, `TEAL`, `ROSE`, `CORAL`; `null` = default neutral), via Room type converters using the same
   label/identifier trade-off as `mode`/`sort`, with the same tolerant-parse fallback.
   The expanded offered set excludes **gold** (accent rule: milestones only) and the **vivid live green**
   (presence only). Sage is admissible: the palette already distinguishes the muted sage
   rarity hue from the live-presence green (`Color.kt`). Every token has a light-scheme
   counterpart (`SteelBlueDark`, `RarityEpicLight`, `RarityUncommonLight`,
   `RarityCommonLight`, plus collection-specific teal, rose, and coral pairs), so the picker stays
   scheme-correct. *Alternative rejected:* free
   color input — violates the "limit to palette" requirement and the accent rule.

5. **Mode drives anatomy; accent drives tint.** Home card per mode: basic = count;
   completion goal = progress bar + `<unlocked>/<total> trophies · <remaining> left` copy;
   deadline goal = countdown + progress;
   ordered queue = next-up row with position. Accent tints the mode-icon chip, progress
   indicator, card surface wash, and a start-edge stripe. Cards use a stronger elevated base
   surface, a low-opacity accent wash, 10dp gaps between cards, and compact internal padding so
   they pop from the Home background without becoming vertically bulky. This keeps the two
   concerns independently testable and lets "no accent" stay a valid default.

6. **Done mark = `collection_members.done` (INTEGER NOT NULL DEFAULT 0).**
   `CollectionMemberSignals` gains `manualDone`; in `CollectionSummary.derive`, ordered-queue
   `nextUp` = first member **not** done, and `queueCompleted` = every member done **or**
   derived `fullyComplete` (OR-composed, so a later-derived completion never conflicts with a
   manual mark). Non-queue modes ignore the mark (it survives mode switches inertly).
   Presentation: member stays in the list, name struck through, card greyed. Like
   add/remove/reorder, the toggle is **buffered in the edit session and reconciled on save** —
   cancel discards it, keeping the screen's one atomic-commit model.

7. **HomeViewModel consolidation: one `combine(collections, observeAllMembers(), library,
   counts)`** replaces the per-collection `flatMapLatest` + per-collection combines. New DAO
   query `observeAllMembers()` (all rows, ordered by collection + sequence); the ViewModel
   groups by `collectionId`. One library/counts subscription instead of N, and a single
   derivation pass per emission.

8. **Schema: additive `MIGRATION_9_10`** — `ALTER TABLE collections ADD COLUMN accent TEXT`
   and `ALTER TABLE collection_members ADD COLUMN done INTEGER NOT NULL DEFAULT 0`; version
   9 → 10, registered in `DatabaseModule` alongside the eight existing migrations. No
   backfill: `null` accent = default styling, `0` done = not done.

9. **Backup: additive fields with defaults** — `BackupCollection.accent: String? = null`,
   `BackupCollectionMember.done: Boolean = false`. The app's shared `Json` instance already
   sets `ignoreUnknownKeys = true` and `coerceInputValues = true`, so new files restore into
   old shapes and old files restore with defaults. `formatVersion` stays 1 — the collections
   section itself was added without a bump, and additive defaulted fields need none. Merge
   keeps existing upsert semantics (backup row wins); unknown accent strings parse tolerantly
   to `null`.

10. **Overview before editor** — the existing-collection route uses one destination with two local
    states: an overview for persisted collections and the existing buffered editor for creation or
    an explicit customization action. The overview has no add-games pool or collection fields;
    those remain in the editor and are reached from a subdued header actions menu. Back from the
    editor returns to the overview for an existing collection, while save/delete still return Home.

11. **Overview metrics come from local repositories** — member playtime comes from cached library
    values, trophy counts come from stored achievement aggregates, and session count comes from a
    new observed `COUNT(*)` projection in `SessionDao` exposed by `SessionRepository`. Missing
    trophy data is shown as unavailable rather than zero; playtime and sessions safely display zero.
    The overview aggregates these values for its summary and repeats the per-game values on larger
    member tiles.

## Risks / Trade-offs

- [FAB covers the last rows] → end spacer sized for FAB clearance inside the lazy list.
- [Per-keystroke recomposition of hundreds of rows] → query kept composable-local; lists
  keyed by `appId`; filter is an O(n) name scan over hundreds — negligible.
- [Double-tap duplicates a new collection] → `saving` flag rejects re-entrant `save()`.
- [Accent dilutes palette semantics] → token set is fixed and documented; gold/vivid green
  excluded; the `document-color-palette` change's accent rule is the contract.
- [Done mark vs later derived completion] → OR-composed; they never disagree.
- [LazyColumn + text field keyboard behavior] → same shape as Library's search field over a
  lazy list, already proven in-app.
- [Mode switch leaves stale done marks] → deliberately kept inert, never deleted.
- [Users cannot find add games from an empty overview] → the empty state provides a restrained
  `Customize collection` action; the full add-games pool remains absent from the overview.
- [Missing metrics make the overview look broken] → playtime/session values default to zero and
  trophy values use an explicit no-data state when no stored counts exist.

## Migration Plan

Single release: bump schema 9 → 10 with the additive migration; installs upgrade in place,
fresh installs create v10 tables. Old backups restore with default accent/no-done; new
backups ignore cleanly on older builds. Rollback path is the configured destructive fallback
(standard for this codebase; collections are restorable from any backup).

## Open Questions

None — checkmark semantics (keep + strike-through + grey), trophy count copy, palette limits,
search scope (add pool only), and overview-first navigation were resolved with the requester
during exploration.
