# Design — Search relevance for games

## Context

Two independent search implementations exist, and neither ranks:

```kotlin
// LibraryViewModel.kt:418-425 — matching(query), applied before sortedFor(key)
game.name.contains(trimmed, ignoreCase = true) ||
    game.genres.any { it.label.contains(trimmed, ignoreCase = true) }

// AddGamesGenreFilter.kt:13-25 — filterAddableGames
game.appId !in memberAppIds &&
    (trimmed.isEmpty() || game.name.contains(trimmed, ignoreCase = true)) &&
    (selectedGenreIds.isEmpty() || game.genres.any { it.id in selectedGenreIds })
```

They have already drifted: the Library matches genre labels, the Add-games pool does not.

`contains` is the only match tier, so a mid-word incidental hit and a prefix hit are indistinguishable.
Order therefore comes entirely from the ambient sort. In the Library that is
`sortedFor(LibrarySortKey)` (`LibrarySorting.kt`), defaulting to playtime descending — which is why
a heavily-played incidental match outranks the obvious intended one. The Add-games pool has no sort
at all and inherits `state.libraryGames` order.

The Library's behavior here is specified, not accidental:

> `Per-list Library sorting` → **Scenario: Sorting combined with search** — *WHEN a search filter is
> active, THEN the matching games are presented in the chosen sort order.*

That scenario is what this change modifies. The `Collection add-game genre filtering` requirement
likewise specifies `contains` normatively ("its name contains the text ignoring case").

Both surfaces filter in memory over already-loaded data — the Library comment is explicit that
"the library is already loaded, so no query per keystroke". Ranking preserves that; it adds a
scoring pass over an in-memory list, not a query.

## Goals / Non-Goals

**Goals:**
- One ranking used by both searches, so they cannot drift again.
- Prefix and word-boundary matches beat incidental mid-word matches.
- The user's chosen Library sort still means something while searching.
- Keep the ranking pure and separately testable, so tier boundaries are cheap to tune later.

**Non-Goals:**
- Fuzzy matching, typo tolerance, edit distance, or transliteration.
- Search over anything beyond name and genre label (not descriptions, not achievements).
- Persisting a "sort by relevance" option as a user-selectable sort key.
- Full-text search or any per-keystroke query.
- The collection form's overall density; Library or collection display modes.

## Decisions

- **Relevance is a tiered score, not a continuous one.** A match resolves to exactly one tier:
  exact name → name prefix → word prefix within name → substring elsewhere in name → genre label
  only.
  *Why:* tiers are explainable, order-stable, and directly testable — each tier is a scenario in the
  spec. A continuous score blends incomparable signals and makes "why is this third?" unanswerable.
  *Alternative rejected:* edit-distance/fuzzy ranking — solves typos, which is not the reported
  problem, and would make the ordering unpredictable for exactly the prefix queries that matter most.

- **Within a tier, the user's chosen sort decides.** Relevance orders across tiers; `comparatorFor(key)`
  orders within one.
  *Why:* this is what preserves the intent of the requirement being modified. The chosen sort still
  governs — it simply no longer reaches across genuinely different match qualities. A user searching
  `Red` with playtime sort still gets their most-played *prefix* matches first.
  *Alternative rejected:* relevance replaces the sort entirely while querying — simpler, but discards
  a persisted preference the user set deliberately, and makes two equally-relevant matches order
  arbitrarily. *Alternative rejected:* sort stays primary with relevance as tie-break — barely changes
  anything, since ties on playtime are rare; it would not fix the reported case at all.

- **Genre-label matches rank below every name match.** A game matching by name and by genre is
  ranked by its name match and shown once.
  *Why:* a query is far more often a title fragment than a genre. Ranking a genre-only match above a
  weak name match would make the `Action` query hide games actually called *Action*.

- **Word-boundary detection is by non-alphanumeric separators plus case transitions at ASCII
  boundaries.** `Red Dead` and `RedDead` both yield a word starting at `Dead`.
  *Why:* Steam titles are inconsistently punctuated. Left at whitespace-only splitting, a large class
  of real titles falls to the substring tier for no user-visible reason.
  *Trade-off:* case-transition splitting is meaningless for scripts without case, which simply fall
  back to separator splitting rather than being handled incorrectly.

- **The Add-games pool adopts genre matching.** It currently matches names only.
  *Why:* the divergence is unintentional — the Library gained genre matching in `add-game-genres`
  and the Add-games text query was not updated, while the *genre filter* beside it was. Two searches
  over the same library disagreeing about what is searchable is a bug regardless of this change.

- **Membership exclusion stays a filter, ahead of ranking.** A game already in the collection is not
  a weak match; it is not a match.
  *Why:* keeps ranking a pure function of query and game, with eligibility decided separately —
  the same separation `filterAddableGames` already has between exclusion and matching.

- **The Library's genre filter selection is transient, reset per visit — not persisted.** It matches
  the collection Add-games genre filter, which is held in `rememberSaveable` and does not survive
  leaving the screen.
  *Why:* the two genre filters are the same control doing the same job, and having one persist while
  the other does not would be arbitrary from the user's side. It also keeps the filter in the same
  category as the achievement sort — a lens on the current view, not a stated preference — where
  `LibrarySortKey` is genuinely the latter and is persisted for that reason.
  *Alternative rejected:* persisting it alongside the sort keys — a stored genre filter is invisible
  until the user notices results missing on a later visit, which is the failure mode a transient
  filter cannot have.

- **The Library search field uses a placeholder, not a floating label, and reserves its trailing
  control unconditionally.**
  *Why:* the reported "text becomes tiny and the field narrows" is two stacked causes. `.height(52.dp)`
  is below what a Material 3 outlined field with a label needs, so the label rises into a box too
  short to hold it and collides with the input; separately, the clear button is composed only when
  the query is non-empty, so the editable region loses its width the moment the user types. A
  placeholder does not float, and reserving the trailing slot keeps the width constant.

- **The Add-games search moves above the configuration form and stays adjacent to its results.**
  Its exact placement is an implementation choice; the spec constrains only that results stay visible
  while typing.
  *Why:* the field currently sits below name, mode, order, target date, time basis, accent, and every
  member row in one `LazyColumn`, so its results render under the keyboard it raises. Specifying the
  outcome rather than the mechanism leaves room to satisfy it with a pinned header or a separate
  surface, which is a density question this change deliberately does not settle.

## Risks / Trade-offs

- **Tier boundaries will be wrong for some queries.** No ranking satisfies every intent. → The
  function is pure, standalone, and covered per-tier by tests, so changing a boundary is a
  one-function edit with immediate verification. This is the main reason for keeping it separate
  from both view models.

- **Existing tests assert the current ordering and will change.** `LibraryGenreMatchingTest` asserts
  `rows.matching("IND")` returns `[2, 3]`; under ranking, `Indie Action` (word prefix in name)
  outranks `Celeste` (genre-only), giving `[3, 2]`. → Expected and correct; the updated assertions
  become the regression guard for the new behavior. `AddGamesGenreFilterTest` and `LibrarySortingTest`
  are affected similarly.

- **Ranking cost is per-keystroke over the whole library.** → Bounded: one pass computing a small
  integer per game, over a list already held in memory, on a collection sized by a personal Steam
  library. No allocation per keystroke beyond the result list, which the current `filter` already
  produces.

- **Genre matching in the Add-games pool widens that pool.** A user typing `act` now also sees every
  Action game. → Ranked below all name matches, so they appear after the intended results rather than
  displacing them, and the requirement being modified already establishes genre as searchable in the
  Library.

## Migration Plan

No persisted state, no schema, no settings key. Behavior-only; revertable by restoring the two
`contains` filters. The `LibrarySortKey` enum and its persisted values are untouched — relevance is
never a stored sort choice.

## Open Questions

- Should an active genre filter in the Library be visually distinct from an active text query, given
  both now narrow the same list? Presentation detail, not spec-visible.
