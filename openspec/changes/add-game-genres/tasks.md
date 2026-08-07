## 1. Genre cache persistence

- [x] 1.1 Add the structured `GameGenre` domain value, ordered JSON codec, `GameGenreCache` Room entity, and DAO flows/queries needed for full-library observation plus missing/stale enrichment selection.
- [x] 1.2 Register `game_genre_cache` in `BacklogiumDatabase`, expose its DAO through dependency injection, bump schema v11 to v12, and add the additive v11-to-v12 migration without synthesizing cache rows for existing games.
- [x] 1.3 Add plain-JVM/Room tests covering ordered round-trip data, checked empty results, missing rows, stale selection, and defensive handling of malformed cached JSON.

## 2. Steam Store metadata source

- [x] 2.1 Add a dedicated Steam Store Retrofit service and DTOs for single-app `appdetails` responses using fixed English labels, retaining only `success`, app data, and ordered genre id/description pairs.
- [x] 2.2 Implement a genre metadata data source that distinguishes successful genre lists, definitive empty/unavailable results, and transient HTTP/network/throttling failures without requiring Steam Web API credentials.
- [x] 2.3 Add fixture-driven tests for ordered genres, empty genres, `success = false`, missing app envelopes, ignored categories/tags, malformed entries, HTTP 429/server errors, and network exceptions.

## 3. Bounded background enrichment

- [x] 3.1 Implement cache freshness and candidate selection with named defaults for a 30-day TTL, missing-before-stale priority, a 25-app batch bound, and at least 500 ms sequential request spacing.
- [x] 3.2 Add a network-constrained unique WorkManager genre worker that writes successful or definitive empty results atomically, preserves last-known data on transient failure, uses retry/backoff, and schedules delayed continuation when eligible games remain.
- [x] 3.3 Add a lightweight genre scheduler call after successful owned-games persistence so normal Steam sync enqueues enrichment without awaiting it or allowing Store failures to alter the sync outcome.
- [x] 3.4 Test fresh-cache skipping, missing/stale ordering, bounded batches, continuation, transient retry with preserved data, negative caching, and the independence of normal Steam sync from enrichment outcomes.

## 4. Shared library genre model

- [x] 4.1 Join the genre-cache Flow into `GameRepository.library`, `goalGames`, and `backlog`, exposing ordered `genres: List<GameGenre>` on `LibraryGame` while mapping missing or malformed rows to an empty list.
- [x] 4.2 Update affected constructors, previews, fixtures, and fake repositories/DAOs for the new cache dependency without changing existing HLTB, playtime, achievement, collection, or backup semantics.
- [x] 4.3 Add repository tests proving cached genres are available offline, preserve Store order, and do not hide a game when genre data is missing or invalid.

## 5. Library genre search

- [x] 5.1 Extend the pure Library matching predicate to match a trimmed query against either game name or any genre label, ignoring case and emitting each game only once.
- [x] 5.2 Change the Library field label to `Search games or genres` while preserving existing sections, selection state, sorting, clearing, and no-match presentation.
- [x] 5.3 Add tests for name-only matches without genre data, partial/case-insensitive genre matches, simultaneous name-and-genre matches, section preservation, no matches, and clearing.

## 6. Game detail genre tiles

- [x] 6.1 Carry ordered genres through `GameDetailViewModel` into the summary UI state without coupling rendering to Store network work.
- [x] 6.2 Render all known genre labels as compact non-clickable surfaces in a wrapping `FlowRow` within the existing game summary, and render no genre section or error placeholder for an empty list.
- [x] 6.3 Add presentation/UI tests for multiple wrapping genres, Store order, empty/unknown genres, and informational non-navigation behavior.

## 7. Collection Add games genre filter

- [x] 7.1 Extract a pure Add games filter and genre-catalog derivation that excludes members, applies name text matching, matches any selected genre id, excludes unknown genres only while a genre filter is active, and de-duplicates/sorts genre choices.
- [x] 7.2 Add saveable transient query and selected-genre state plus a compact Genres control, multi-select sheet/list, removable wrapping selection chips, and clear-all action to the collection management form.
- [x] 7.3 Keep selected text/genres active while individually adding games and verify that selecting or clearing filters never mutates draft membership automatically.
- [x] 7.4 Add tests for no selection, one genre, additive `OR` selection, text-plus-genre `AND`, unknown metadata, no-match state, preserved filters after add, clear-all behavior, and non-destructive membership.

## 8. Validation and handoff

- [x] 8.1 Run `./gradlew.bat testDebugUnitTest --offline` and `git diff --check`, resolving all regressions including test fakes affected by new DAO or model contracts.
- [ ] 8.2 Validate `add-game-genres` with OpenSpec and confirm every scenario is represented by automated coverage or an explicit manual check.
- [ ] 8.3 On a device or emulator with a representative Steam library, verify background backfill/continuation, offline cached rendering, detail tile wrapping, genre text search, additive collection filtering, filter preservation after adding, and graceful behavior for unknown genres; record any unverified Store throttling behavior explicitly.
