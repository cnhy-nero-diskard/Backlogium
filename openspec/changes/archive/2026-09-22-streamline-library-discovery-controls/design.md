## Context

Library currently places search, density, HLTB review, genre filtering, coverage filtering, and Family Shared filtering in two dense rows above independently sorted Focus and Your games sections. Selection begins primarily through long press, while filter state is split across ViewModel and local composable state. The existing density modes, list sorting, wishlist placement, HLTB batch flow, and transient-on-leave filters must remain intact.

## Goals / Non-Goals

**Goals:**

- Give search and active filters visual priority while retaining every expert control.
- Make all filter combinations produce truthful, recoverable empty states.
- Scale genre selection and expose selection mode without relying on memory or dexterity.
- Consolidate Library UI state enough that behavior can be unit tested without Compose details.

**Non-Goals:**

- Changing search ranking, sort definitions, density contents, HLTB matching, Focus membership, or wishlist behavior.
- Persisting transient filters across visits.
- Redesigning game rows or detail screens.

## Decisions

### 1. Represent filters as one immutable presentation value

Introduce a `LibraryFilters` value containing query, selected genre ids, not-covered-only, and Family-Shared-only. Derive visible lists, active chips, a human-readable empty-result reason, and clear operations from this value. Keep density and per-section sorts outside it because they persist independently.

This removes the current path where Family Shared participates in filtering but is absent from empty-state recovery.

### 2. Use a search-first control bar and a labeled tools surface

The first row contains the full-width search field plus a labeled Filters action that carries the active count. Active filter chips render directly below. Density, HLTB enrichment, and less-frequent display tools move into a labeled Library tools bottom sheet or menu. Section-local sort controls stay beside their headings because their scope is local and visible.

An icon-only overflow was rejected because the critique identified recognition and discoverability as problems. Permanently showing every tool was rejected because that is the current overload.

### 3. Surface HLTB attention outside the tools sheet

When a batch is active, keep its progress card in the main list. When review items exist, show a compact attention row/count. Starting ordinary or forced enrichment lives in Library tools. This preserves urgent state visibility while demoting maintenance commands.

### 4. Make the genre sheet searchable and lazy

Use a sheet with a pinned search field, selected summary/clear action, and `LazyColumn` of matching genre rows. Selection state is keyed by stable genre id and remains intact when search hides a selected item. Use checkbox-style rows rather than full-width chips for faster scanning of a long list.

### 5. Add an explicit Select action while preserving long press

Library tools exposes `Select games`; selection mode then uses the existing selection bar. Add `onLongClickLabel`, selected-state semantics, and a named toggle action to list and grid cards. Long press remains an accelerator, not the only entrance.

### 6. Localize user-visible presentation

Move screen copy, quantities, and formatted values into resources. Build empty-state copy from resource variants keyed by active-filter categories instead of concatenating English fragments.

## Risks / Trade-offs

- **[Risk] Moving density/HLTB controls makes expert actions slower.** → Keep the tools entry labeled and one tap away; surface active progress and review counts in the main flow.
- **[Risk] A unified filter object causes broad recomposition.** → Keep it immutable/stable and derive filtered lists with memoized ViewModel flows or keyed `remember` blocks.
- **[Risk] Bottom-sheet controls are harder to automate.** → Give the tools entry, sheet, filters, and selection mode stable semantics/test tags.
- **[Risk] Filter copy becomes combinatorial.** → Prefer one general combined-filter message plus visible removable chips instead of generating every English combination.

## Migration Plan

No stored-data migration is required. Introduce and test the filter model first, fix recovery, then replace the control hierarchy, genre picker, selection semantics, and resources. Existing density/sort preferences remain compatible.
