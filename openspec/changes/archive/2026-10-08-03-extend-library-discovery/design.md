## Context

See `proposal.md` and `specs/app-ui/spec.md`. `WishlistViewModel` already exposes cached entries and availability separately; Library currently hides the wishlist section when owned-game filters are active. `LibrarySortKey` and `LibrarySorting` own persisted per-section ordering. Game arrival time is already recorded as `firstSeenAt`, with baseline and legacy games legitimately undated. `VisibilityChangeDialog` currently presents detailed XP, level, and Focus effects.

## Goals / Non-Goals

**Goals:**

- Search cached wishlist names without network work on every keystroke or confusing them with owned/shared games.
- Add a truthful date-added ordering while retaining stable search relevance and old saved sort keys.
- Shorten Hide confirmation while keeping material effects visible.

**Non-Goals:**

- Changing wishlist data collection, Steam purchase-date inference, or Library visit lifetime (defined by `02-retain-library-visit-context`).
- Adding the collection quick action from issue #168 or trophy legibility work from draft PR #178.

## Decisions

### Filter wishlist cache separately from owned games

Derive wishlist title matches from `WishlistUiState.entries` for a nonblank text query and present them in a labeled wishlist result section even if its ordinary section is collapsed. Keep the owned/shared search pipeline and ranking intact. Deduplicate by Steam app ID in favor of an owned/shared result. Hide wishlist-only results when an owned-only genre, coverage, or Family Shared filter is active, because wishlist data cannot satisfy those predicates. Keep availability and stale-price messaging alongside results, and do not turn a failed wishlist read into a no-match claim. The alternative of merging wishlist entries into owned game models would expose tracking and collection actions to games the player does not own.

### Add a new persisted sort key using existing arrival data

Add `ADDED_RECENTLY` as a new `LibrarySortKey` value with descending default, and surface it independently for Focus and Your games. Extend `LibrarySorting` and the UI projection to use `firstSeenAt`. Sort known times by the selected direction, keep null times last in both directions, then tie-break by normalized title and app ID. Search relevance remains the primary key when a query is active. Existing enum names, stored preferences, and Recent activity behavior remain intact. The alternative of reusing Recent activity would conflate two-week play with library arrival; the alternative of substituting sync time for nulls would invent purchase order.

### Condense Hide copy without dropping consequential facts

Give the Hide dialog a short consequence and restore sentence, then show compact XP/level and Focus effects only when they change. Keep explicit Hide/Cancel controls and the existing unhide behavior. The alternative of removing all recomputed effects would conceal a material level drop or Focus change.

## Risks / Trade-offs

- [Wishlist reads fail or cached prices are stale] → Preserve availability labels and dated price states in search results.
- [A title appears in both sources] → Deduplicate by app ID while retaining the owned action set.
- [Baseline and legacy arrival dates are null] → Keep unknowns last with stable tie-breaks and explain the data meaning.
- [Search ranking changes unexpectedly] → Add the new sort only as a secondary order after relevance for owned results.

## Migration Plan

No database migration is expected: `firstSeenAt` and wishlist cache already exist. The new sort-key name is additive to stored preferences; older names retain their behavior, and rollback falls back safely if a newer value is encountered.
