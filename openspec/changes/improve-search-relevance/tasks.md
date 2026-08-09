# Tasks

## 1. Shared ranking

- [ ] 1.1 Add a pure, Android-free ranking function for game search, in the same shape as
      `HistoryGrouping` and `AddGamesGenreFilter`: given a query and a game's name and genre labels,
      resolve exactly one match tier or no match.
- [ ] 1.2 Implement the tiers, strongest first: exact name, name prefix, word prefix within the name,
      substring elsewhere in the name, genre label only. Case-insensitive throughout.
- [ ] 1.3 Implement word-boundary detection over non-alphanumeric separators plus ASCII case
      transitions, so both `Red Dead` and `RedDead` yield a word starting at `Dead`.
- [ ] 1.4 Rank a game matching by both name and genre by its name tier, and return it once.
- [ ] 1.5 Treat a blank or whitespace-only query as "no query": every game eligible, ranking absent.
- [ ] 1.6 Unit-test each tier independently, including the reported case — `Red` must rank
      `Red Dead Redemption` above a longer, more-played name containing `red` mid-word.
- [ ] 1.7 Unit-test ordering stability: two games in the same tier must order by the supplied
      tie-break, never arbitrarily.

## 2. Library search

- [ ] 2.1 Rework `matching()` in `LibraryViewModel` into a ranked projection over the shared function.
- [ ] 2.2 Compose ranking with `sortedFor(key)` so relevance orders across tiers and
      `comparatorFor(key)` orders within a tier.
- [ ] 2.3 Confirm clearing the query restores the chosen sort as the sole order, with no residual
      relevance ordering.
- [ ] 2.4 Confirm section structure is preserved: each of Focus and Your games ranks independently
      and keeps its heading when it has matches.
- [ ] 2.5 Update `LibraryGenreMatchingTest` for the new ordering (`matching("IND")` becomes
      `[3, 2]` as `Indie Action` outranks the genre-only match on `Celeste`), and extend it to cover
      name-beats-genre and prefix-beats-mid-word.
- [ ] 2.6 Update `LibrarySortingTest` where it asserts ordering under an active query.

## 3. Library search field

- [ ] 3.1 Remove the fixed `.height(52.dp)` and the `.padding(bottom = 8.dp)` that shrink the input
      box below what a Material 3 outlined field needs.
- [ ] 3.2 Replace the floating `label` with a `placeholder`, keeping the wording that communicates
      both games and genres are searchable.
- [ ] 3.3 Reserve the trailing clear control unconditionally so the field's width does not change
      when the first character is typed.
- [ ] 3.4 Verify by hand: focusing the empty field and typing changes neither the field's width nor
      the size of the text in it.

## 4. Library genre filter

- [ ] 4.1 Add a multi-select genre control to the Library search, reusing `genreFilterCatalog` and
      the existing modal sheet pattern rather than duplicating either.
- [ ] 4.2 Apply the selection as a narrowing filter ahead of ranking; ranked order is unchanged
      within the narrowed set.
- [ ] 4.3 Hold the selection transiently in `rememberSaveable`, matching the collection Add-games
      genre filter. It survives rotation and process death within the visit, and resets on leaving
      the Library. Do not add a settings key.
- [ ] 4.4 Confirm an active genre filter with no text query lists every carrier of a selected genre.

## 5. Collection Add-games pool

- [ ] 5.1 Adopt the shared ranking in `filterAddableGames`, keeping membership exclusion as a filter
      applied ahead of ranking.
- [ ] 5.2 Extend its text query to match genre labels, closing the divergence from the Library.
- [ ] 5.3 Update `AddGamesGenreFilterTest` for ranked output and genre-label matching; add a case
      asserting an already-member game is absent rather than merely ranked low.
- [ ] 5.4 Confirm text query and genre selection still combine as the existing requirement specifies:
      a game must match the text and carry at least one selected genre.

## 6. Add-games search placement

- [ ] 6.1 Reposition the Add-games search so its results stay visible while typing, rather than
      rendering below the raised keyboard.
- [ ] 6.2 Confirm the offered-games list remains scrollable with the keyboard raised, and that the
      field does not scroll out of view while typing.
- [ ] 6.3 Confirm adding a game leaves the query and genre selection intact, per the existing
      requirement.

## 7. Validation

- [ ] 7.1 Walk every scenario in `specs/app-ui/spec.md` for all three modified requirements.
- [ ] 7.2 Verify the reported case end to end: search `Red` in the Library under the default playtime
      sort and confirm Red Dead Redemption is presented before incidental mid-word matches.
- [ ] 7.3 Confirm both searches agree on what is searchable for the same query.
- [ ] 7.4 `./gradlew :gamification:test :app:testDebugUnitTest`.
- [ ] 7.5 `openspec validate improve-search-relevance --strict`.
