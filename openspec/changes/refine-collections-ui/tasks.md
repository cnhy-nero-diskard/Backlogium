# Tasks — Refine the collections UI

> **Invariants to preserve:** collections and memberships stay app-owned state the Steam sync
> worker never touches; the management screen's buffered edit model (cancel discards, save
> persists atomically); palette semantics — gold stays milestone-reserved, vivid green stays
> live-presence-reserved; no network calls anywhere in the feature.

## 1. Schema and persistence

- [x] 1.1 New `domain/CollectionAccent.kt` enum (`STEEL_BLUE`, `VIOLET`, `SAGE`, `SLATE`, `TEAL`,
      `ROSE`, `CORAL`) with a
      tolerant name parse (unknown → null); `Collection` gains `accent: CollectionAccent? = null`,
      `CollectionMember` gains `done: Boolean = false`
- [x] 1.2 `Converters`: nullable `CollectionAccent` converters, tolerant-parse fallback, same
      label/identifier trade-off note as the mode/sort converters
- [x] 1.3 `BacklogiumDatabase`: version 9 → 10; additive `MIGRATION_9_10` (`ALTER TABLE
      collections ADD COLUMN accent TEXT`; `ALTER TABLE collection_members ADD COLUMN done INTEGER
      NOT NULL DEFAULT 0`); register in `DatabaseModule`
- [x] 1.4 `CollectionDao`: `updateDetails` gains accent; new `setMemberDone(collectionId, appId,
      done)`; new `observeAllMembers()` (all members ordered by collectionId, orderIndex) and a
      one-shot `getAllMembers()` for backup export
- [x] 1.5 `CollectionRepository`: plumb accent through `create`/`updateDetails`; expose
      `setMemberDone` and the all-members flow
- [x] 1.6 Add persisted `CollectionTimeBasis` for deadline collections, with a v10 → v11 additive
      migration, tolerant Room parsing, and backup export/restore defaults

## 2. Domain: queue done semantics

- [x] 2.1 `CollectionMemberSignals` gains `manualDone: Boolean`; in `CollectionSummary.derive`,
      ordered-queue `nextUp` = first member not marked done, `queueCompleted` = every member
      `manualDone || fullyComplete`; done marks ignored for non-queue modes
- [x] 2.2 `CollectionSummaryTest`: next-up skips done members; a queue of members without HLTB data
      completes via marks alone; mark OR-composes with derived completion; marks inert in non-queue
      modes; unmarking restores next-up eligibility
- [x] 2.3 Extend `CollectionBanner` with aggregate unlocked and total achievement counts when
      stored data exists; render completion-goal Home cards as `<unlocked>/<total> trophies ·
      <remaining> left`, with a no-data fallback, and cover the derivation in JVM tests
- [x] 2.4 Derive deadline planning from the selected HLTB basis: subtract stored playtime from
      each known estimate, report unknown-duration members, and expose positive buffer/negative
      shortfall arithmetic in JVM tests

## 3. Backup compatibility

- [x] 3.1 `BackupCollection` gains `accent: String? = null`; `BackupCollectionMember` gains
      `done: Boolean = false` (formatVersion unchanged — additive defaults)
- [x] 3.2 `BackupExportMapper`: carry the accent name and done flag into the backup DTOs
- [x] 3.3 `BackupMergeEngine`: parse accent tolerantly (unknown → null), write done on the member
      upsert
- [x] 3.4 `BackupMergeEngineTest`: a legacy collection file without the new fields restores with
      defaults; an unknown accent string falls back to null; done round-trips through export/merge

## 4. Management screen restructure (lag fix + floating save + search)

- [x] 4.1 Rebuild the form as **one flat `LazyColumn`**: `item` blocks for name, mode, order,
      target date, accent, and section headers; keyed `items` for members and addable games; a
      trailing spacer sized for FAB clearance
- [x] 4.2 Floating save: FAB overlaid bottom-end in a `Box`; dimmed presentation when the name is
      blank; `saving` flag in `CollectionViewModel.save()` rejects re-entrant taps
- [x] 4.3 Move delete from the form's end into the header row (`IconButton`, edit mode only)
- [x] 4.4 Add-games search: composable-local query filtering `addableGames` by case-insensitive
      name containment (pool only); the no-match state renders inside the list beneath the field so
      it can always be cleared
- [x] 4.5 Accent picker: selectable chips for the seven palette tokens plus default/none, buffered
      in the edit session and persisted on save
- [x] 4.6 Expand the accent picker to seven palette-safe tokens, adding teal, rose, and coral
      with dark/light theme mappings and tolerant persistence through the existing enum storage
- [x] 4.7 Existing collections open to a read-only overview; keep creation on the setup form and
      place customization, including add games, behind the secondary collection-actions menu
- [x] 4.8 Add overview summary/member metrics from cached playtime, achievement counts, and
      observed per-game session counts; render selected games as larger highlighted tiles
- [x] 4.9 Add the deadline setup basis selector and overview hindsight card, including the direct
      date-picker shortcut when the selected estimate does not fit

## 5. Ordered-queue checkmark

- [x] 5.1 `CollectionViewModel`: buffer done toggles in the edit session alongside the member
      sequence; load stored done state when opening an existing queue; reconcile marks on save via
      `setMemberDone`
- [x] 5.2 `MemberRow`: checkmark control shown in queue mode only; done members render struck
      through and greyed while staying in the list

## 6. Home: separation, mode styling, accent

- [x] 6.1 `CollectionsSection`: separate cards with `Arrangement.spacedBy`
- [x] 6.2 `CollectionCard`: mode-specific anatomy — mode icon chip plus progress bar (goal modes),
      countdown (deadline), next-up row with position (ordered queue) — replacing the single text
      line
- [x] 6.3 Accent tint: a theme helper mapping `CollectionAccent` to dark/light tokens
      (`SteelBlue`/`SteelBlueDark`, `RarityEpic`/`RarityEpicLight`,
      `RarityUncommon`/`RarityUncommonLight`, `RarityCommon`/`RarityCommonLight`,
      `CollectionTeal`/`CollectionTealLight`, `CollectionRose`/`CollectionRoseLight`,
      `CollectionCoral`/`CollectionCoralLight`) applied to the
      icon chip, progress indicator, and a start-edge stripe
- [x] 6.4 `HomeCollectionCard` carries the accent and feeds member done marks into the banner
      derivation
- [x] 6.5 Make collection cards visually pop from the Home background with an elevated base
      surface and low-opacity accent wash; add 10dp inter-card spacing and compact internal
      padding so the cards remain easy to scan

## 7. HomeViewModel consolidation

- [x] 7.1 Replace the per-collection `flatMapLatest` + per-collection combines with one
      `combine(collections, observeAllMembers(), library, counts)` grouping members by
      collectionId, deriving every card in a single pass
- [x] 7.2 Confirm the Home collections section's existing guarantees still hold: empty state,
      offline rendering, no displacement or demotion of the level/XP/quest/streak/now-playing
      surfaces

## 8. Verification

- [x] 8.1 JVM unit tests green (`CollectionSummaryTest`, `BackupMergeEngineTest`, plus any touched
      suites, including aggregate trophy-count coverage)
- [x] 8.2 `assembleDebug` builds cleanly; Room schema export (if configured) reflects v10
- [ ] 8.3 Manual pass: management screen smooth with a large library; floating save reachable at
      any scroll position; search filters the add pool; accent choice persists across reopen;
      checkmark strikes through the row and advances the home banner's next-up; existing collection
      entry opens the overview with larger metric tiles; customization and add games stay behind
      collection actions; home cards spaced and mode-styled
- [ ] 8.4 Manual pass: each deadline basis changes the estimate, overview shows time remaining and
      buffer/shortfall copy, missing basis data is explicit, and Change deadline updates the card

