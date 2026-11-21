## Context

See `proposal.md` for scope and issue traceability. At `004a508f`, `CollectionRepository` already owns atomic editor saves and direct membership writes; `CollectionDao.addMember` ignores duplicates. Membership is a soft app-ID reference and hidden memberships are retained on storage reads. `SmartCollectionFeed` supplies Home and Collections from one derivation, combining Steam last-played with `MAX(COALESCE(endAt, startAt))` session evidence. Favorites and recent-play kinds are absent.

Issue #167's completed changes already implement the five-minute Library visit policy, wishlist matching, Added recently, and shorter Hide copy. Their checked tasks and verification records are baseline evidence, not work to repeat. All three new changes add distinct requirement names; later main-spec sync must preserve those existing deltas.

## Goals / Non-Goals

**Goals:** Keep app-owned preferences independent of Steam writes; expose domain models for new UI; share derivation and eligibility across entry points; keep direct actions observable and safe during concurrent edits.

**Non-Goals:** Redesign achievement selection, alter Focus or XP, persist smart membership, apply roulette plans, archive deadlines, or add polling. Build the functionality before refining affected cards and screenshots.

## Decisions

### 1. A dedicated app-owned preference table

Add `game_preferences`, keyed by `appId`, with an explicit `isFavorite` value. Retain false rows so an intentional clear can round-trip. Use a soft game reference, matching collection/hidden-game retention: a temporarily missing game cannot cascade-delete the preference. A preference repository exposes plain domain values and transactional mutations guarded against account reset; UI must not import entities or DAOs. No favorite field is added to Steam's insert/update payload. Shared-to-owned conversion retains the same app ID.

The second change extends this table with an optional artwork variant without redefining favorites. Account reset clears it transactionally. Hide/unhide filters its presentation without deleting it. Explicit shared-game removal keeps an inert preference keyed by app ID, as it has no visible member while that game is excluded.

Alternative: store favorites as custom collection membership. Rejected because Favorites is derived and would gain editable membership/queue semantics. Reusing `isGoal` is also rejected because Focus already affects progression and History.

### 2. One derived snapshot, with defined local-date boundaries

Extend the existing feed with favorite preferences and new smart IDs. Preserve the existing kinds' relative order and append their current rules unchanged after Favorites and Played recently. Use the latest supported Steam/session instant, including open meaningful sessions, and inject current instant/date/zone for deterministic decisions. Played recently includes local dates from `today - 13 days` through today and excludes instants later than now. Manual minutes and undated history never supply timestamps. React to the existing date/zone provider; no new network job is needed.

Both screens use the same derived members, counts, labels, and hide settings. Keep the existing empty-list policy: an empty Favorites card is absent, with the detail heart providing the discovery entry point. Preserve Completed's achievement-first rules and disclosure.

Alternative: use Steam `playtime2Weeks > 0` alone. Rejected because cached rolling totals can become stale offline and omit shared-game sessions.

### 3. Reuse mutations while tightening the domain boundary

Expose a plain picker model containing eligible custom collection identity/name and membership state. The repository rechecks target existence and game eligibility inside the mutation transaction. Add operations preserve existing order/done values; direct removal compacts queue order where necessary without altering surviving done marks or hidden memberships. Reconcile concurrent editor saves against their committed baseline so a stale editor cannot silently discard a shortcut addition; return a refresh/retry conflict rather than overwrite a changed membership set.

Each Library density offers a labeled per-game action menu; keep long-press reserved for the existing achievement selection mode. The picker has an explicit empty/creation state and disables already-member additions. Creation uses the existing pushed route and Library visit holder. Derived lists never appear as writable targets. The third change will narrow the same target query to active collections.

Alternative: save shortcuts by rebuilding the full collection editor draft. Rejected because it can overwrite concurrent members and hidden retention.

### 4. Compatible preference backup records

Add an optional `gamePreferences` section to current version-2 backups and snapshots; do not change accepted versions 1/2 or their required session provenance. A missing section means no preference updates. A present record carries app ID and explicit favorite boolean; imports replace that field only for represented app IDs. Export retained rows even when their game is absent. Validate types, positive IDs, and duplicate keys before the existing transaction. Keep warned cross-account merging and SteamID behavior unchanged.

The second change will add a separately presence-aware artwork selection value; older favorite-only records must not clear artwork. Rejecting all older backups or bumping the format unnecessarily would break existing compatibility.

## Risks / Trade-offs

- [Card counts diverge from contents] -> Extend the shared feed, not screen-specific queries.
- [Stale editor overwrites a shortcut] -> Validate membership baseline atomically and publish a retry conflict.
- [Manual shared minutes appear recent] -> Admit only dated Steam/session evidence, with tested date/zone boundaries.
- [A legacy import clears preferences] -> Distinguish omitted records from explicit false values.
- [Dense actions become inaccessible] -> Verify list/both grids, content descriptions, 48dp targets, large fonts, and detail entry-point parity.

## Migration Plan

Use an additive Room migration from the actual schema at apply time (currently 42), with no destructive fallback. Missing preferences mean unfavorited. Add the table to account reset, export snapshots, validation, and transactional import. Run migration-chain/retention checks and focused preference, derivation, membership, backup, and UI checks; record required screenshot baselines alongside UI changes. A rollback of application behavior must preserve the new table and backups; do not downgrade the database destructively. No deployment or spec sync is part of this proposal.
