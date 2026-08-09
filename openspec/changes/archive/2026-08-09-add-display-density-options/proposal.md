## Why

The Library and the collection overview both list games one way: a full-width row carrying icon,
name, playtime, a completion bar, achievement and XP badges, and a trailing control. That row is the
right shape when comparing a few games closely, and the wrong shape when scanning a large library —
a 900-game library is roughly 900 screens of scrolling at one row per line, with no way to trade
detail for reach.

The collection management form has the opposite problem. Its configuration is a vertical stack of
six sections, each a `SectionLabel` at `titleMedium` above a chip row, at 12dp spacing:

```
Name → Mode → Order → Target date → Time estimate basis → Accent → Games → Add games
```

Every option is full-width and equally weighted, so settings that are chosen once and rarely revisited
occupy as much of the form as the ones being actively edited, and the games below them are pushed far
down the scroll.

## What Changes

- Offer the Library a choice of display densities: the existing list, plus grid layouts that trade
  per-game detail for the number of games visible at once.
- Offer the same choice on the collection overview's member list.
- Define what each density shows as a ladder, so detail is dropped in a fixed order rather than
  arbitrarily per surface: identity first, then playtime, then completion progress, then badges.
- Persist each surface's chosen density, alongside the existing Library sort selections.
- Compact the collection management form's configuration so it occupies materially less vertical
  space before the games, without removing any option.

**Not in scope:** changing what any game row can show, the Library's sections or sorting, the search
and genre filtering being reworked by `improve-search-relevance`, and the collection description,
ordering, and delete confirmation being added by `improve-collection-organization`.

## Capabilities

### Modified Capabilities

- `app-ui`: `Library screen` — its scenarios currently guarantee that a game "displays its name, icon,
  and playtime, and a progress indicator", which cannot hold at every density. Those guarantees become
  density-qualified, with a floor that holds at all densities. `Collection management screen` gains a
  density choice for the overview's member list and a compactness requirement for the configuration
  form.

### New Capabilities

None.

## Impact

**Sequencing**

⚠️ `improve-collection-organization` also modifies `Collection management screen`, adding the
description field and the delete confirmation. This change's delta for that requirement is written on
top of that version. Apply this change after it, and do not re-derive the delta from the archived
spec.

`improve-search-relevance` repositions the Add-games search within the same form. It modifies a
different requirement (`Collection add-game genre filtering`), so there is no spec conflict, but both
changes edit `CollectionForm` and the density work should assume the search has moved.

**Affected code**

- `ui/library/LibraryScreen.kt` — `GoalGameRow` and `BacklogGameRow` are currently near-identical
  bodies differing only in their `game` type; the density ladder is a chance to express the shared
  content once rather than twice more per density.
- `ui/collections/CollectionScreen.kt` — `CollectionGameCard` for the overview, and `CollectionForm`
  for the configuration compaction.
- `data/local/SettingsDataStore.kt`, `domain/` — two new persisted preferences, following the
  `LIBRARY_FOCUS_SORT` / `LIBRARY_ALL_SORT` pattern exactly, including tolerating an unknown stored
  value by falling back to the default.
- `ui/components/GameArt.kt` — `GameIcon` hardcodes its size and shape; grid densities need it
  parameterized. Note `polish-game-surfaces` also needs a shape parameter here for circular
  thumbnails.

**Interaction risk**

The Library supports long-press multi-select, with the selection affordance and the goal-management
control living in the row's trailing edge. A compact grid cell has no trailing edge to put them in,
so selection needs a placement that survives every density. This is the change's main unknown.

**Not persisted state that changes meaning**

Density is presentation only. No derived value, no XP, no session, and no membership depends on it.
