## Why

Searching the Library for `Red` buries Red Dead Redemption below unrelated games whose names merely
contain the letters `red` somewhere. The cause is that neither search ranks anything. Both are
boolean filters:

```kotlin
// LibraryViewModel.kt:423
game.name.contains(trimmed, ignoreCase = true) || game.genres.any { ... }

// AddGamesGenreFilter.kt:22
game.name.contains(trimmed, ignoreCase = true)
```

Survivors keep whatever order the ambient sort produced — for the Library that defaults to playtime
descending. So a mid-word incidental match on a 400-hour game outranks a prefix match on a game the
user is plainly looking for, and `contains` gives the two identical standing.

The Library's ordering under search is currently specified as exactly this behavior
(`Per-list Library sorting` → `Sorting combined with search`), so correcting it is a deliberate
requirement change, not a defect fix.

Two adjacent problems make the same searches harder to use, and are cheapest to fix while the search
surfaces are already open:

- The Library search field is given `.height(52.dp)` with a floating `label`, below the height a
  Material 3 outlined field with a label needs. On focus the label rises into a box too short to
  hold it and collides with the input text, and the clear button appears only once text is entered,
  narrowing the editable region mid-typing.
- In the collection management form, the Add-games search field sits inside the same `LazyColumn` as
  the entire configuration form, below name, mode, order, target date, time basis, accent, and every
  existing member row. Typing raises the keyboard over the results the field produces, so the field
  must be used blind.

## What Changes

- Introduce one shared, pure relevance ranking for game search, scoring a match by how it matched:
  exact name, name prefix, word prefix within the name, substring elsewhere in the name, then genre
  label. Higher-relevance matches are presented before lower-relevance ones.
- **BREAKING (behavioral):** while a Library search is active, relevance determines the presented
  order and the user's chosen sort orders games *within* equal relevance rather than across all
  matches. Clearing the search restores the chosen sort as the sole order. This modifies the
  existing `Sorting combined with search` scenario.
- Apply the same ranking to the collection Add-games pool, which today ranks nothing and matches
  names only.
- Extend the collection Add-games search to match genre labels, as the Library's already does, so
  the two searches no longer disagree about what is searchable.
- Fix the Library search field's geometry so focusing it does not shrink or crowd the input, and so
  the field's width does not change as text is entered.
- Add a genre filter to the Library search, reusing the multi-select control the collection
  management screen already provides.
- Move the collection Add-games search out from under the configuration form so results are visible
  while typing.

**Not in scope:** the collection form's overall density, Library or collection display modes
(grid/list), and any change to what a search matches beyond adding genre labels to the Add-games
pool. Search remains an in-memory filter over already-loaded data; no query is issued per keystroke.

## Capabilities

### New Capabilities

None. This change modifies existing search behavior.

### Modified Capabilities

- `app-ui`: `Library search` gains relevance ordering and genre filtering, and gains requirements on
  the search field's stability under focus and input. `Per-list Library sorting` has its
  `Sorting combined with search` scenario changed, since the chosen sort no longer orders matches
  across differing relevance. `Collection add-game genre filtering` has its text-matching rule
  changed from a `contains` test to the shared ranking, and gains genre-label matching.

## Impact

**Affected code**

- New shared ranking function, pure and Android-free, in the same shape as `HistoryGrouping` and
  `AddGamesGenreFilter` — both already pure with deterministic JVM tests.
- `ui/library/LibraryViewModel.kt` — `matching()` becomes a ranked projection; the interaction with
  `sortedFor(key)` changes.
- `ui/library/LibrarySorting.kt` — comparators become the within-relevance tie-break rather than the
  top-level order while a query is active.
- `ui/collections/AddGamesGenreFilter.kt` — `filterAddableGames` adopts the shared ranking and gains
  genre matching.
- `ui/library/LibraryScreen.kt` — `SearchField` geometry; genre filter control.
- `ui/collections/CollectionScreen.kt` — Add-games search relocation.

**Affected tests**

`LibraryGenreMatchingTest` asserts result order under the current input ordering and will change
under ranking (for the query `IND`, `Indie Action` outranks a genre-only match on `Celeste`).
`AddGamesGenreFilterTest` and `LibrarySortingTest` are also affected. These are expected updates,
not regressions.

**Dependencies**

None. The ranking is plain Kotlin; the genre multi-select control already exists.

**Risk**

The ranking's tier boundaries are a judgment call and will be wrong for some queries. Keeping the
function pure and separately tested is what makes tier changes cheap to make and verify later.
