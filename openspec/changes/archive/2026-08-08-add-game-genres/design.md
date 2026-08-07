## Context

Backlogium's authenticated `GetOwnedGames` response supplies app id, name, icon hash, and playtime, but no genres. The `Game` row is rebuilt from that response during every normal poll, and `LibraryGame` joins only Steam game facts with cached HLTB data. Library and collection Add games searches are currently in-memory name filters, while game detail renders from the same shared library model.

Steam's Store `appdetails` response exposes an ordered `genres` array containing broad genre ids and labels. It uses a different host from the documented Steam Web API, accepts one app id per usable request, and is not part of the authenticated polling contract. It therefore has to be treated as fallible enrichment rather than a prerequisite for core sync.

The database is currently Room schema v11. Genres are Steam-owned, recoverable metadata: they need offline caching but do not belong in the user-authored backup contract.

## Goals / Non-Goals

**Goals:**

- Cache broad Steam Store genres once and expose one ordered genre model to game detail, Library search, and collection editing.
- Keep normal owned-game/playtime synchronization fast and successful even when Store metadata is unavailable or throttled.
- Backfill existing libraries gradually and refresh stale metadata without repeatedly requesting definitive empty results.
- Keep all three user experiences offline-first after metadata has been cached.
- Make matching rules deterministic and plain-JVM testable.

**Non-Goals:**

- Community tags such as Souls-like, Metroidvania, mood, perspective, or multiplayer features.
- User-authored genre overrides or custom genres.
- Dynamic collections whose membership changes automatically with genre metadata.
- Bulk-adding every game that matches a selected genre.
- Genre analytics, Home-card genre labels, or navigation from a detail tile in this release.
- A backup-format revision for remotely recoverable genre data.

## Decisions

### 1. Use a dedicated best-effort Steam Store client

Add a small `SteamStoreApi`/data source with its own Retrofit base URL and DTOs for the Store `appdetails` envelope. Requests use a single app id and a fixed English language so labels remain stable for the app's currently English UI. Only `success`, `data`, and ordered `genres { id, description }` are retained; categories and community tags are ignored.

This client does not use the user's Web API key. Keeping it separate from `SteamApi` makes the unsupported reliability boundary visible and prevents Store failures from being mistaken for authenticated Steam sync failures.

**Alternative considered:** derive genres from HLTB search data. Rejected because the current HLTB integration does not retain genre results, depends on title matching rather than Steam app ids, and would couple genre completeness to an optional matching workflow.

### 2. Store one atomic genre-cache row per game

Add a Room `game_genre_cache` entity keyed by `appId`, with an ordered JSON payload of `{ id, label }` values and `checkedAt`. A successful Store result writes the complete replacement list atomically. A definitive Store `success = false` or successful response with no usable genres writes an empty list with a fresh `checkedAt`; absence of a cache row means not checked yet.

This separate table avoids modifying or accidentally clearing genre state when `SteamSyncWorker` reconstructs `Game` rows. It also represents successful empty results without a sentinel genre and preserves source ordering. The DAO exposes the full cache as a Flow and targeted missing/stale reads for enrichment.

**Alternative considered:** add genre JSON directly to `Game`. Rejected because every owned-game poll rebuilds that entity and would have to preserve independently refreshed Store fields forever.

**Alternative considered:** normalize every genre into a child row. Rejected for the initial slice because all consumers already filter an in-memory library, while an atomic JSON row makes ordered replacement and empty-result caching simpler. The domain model remains structured; it is not a comma-separated display string.

### 3. Enrich through an independent bounded WorkManager chain

After a successful owned-games persistence pass, the sync path only enqueues unique genre work and continues; it never awaits Store requests. The genre worker requires network connectivity, selects missing records before stale records, and processes a bounded batch sequentially. Initial defaults are a 30-day freshness window, at most 25 apps per run, and at least 500 ms between request starts. These values live as named constants so they can be tuned without changing filtering behavior.

If eligible games remain, the worker schedules a delayed continuation under the same unique-work identity. A transient network, 429, or server failure preserves any prior cache and uses WorkManager retry/backoff. A definitive empty result is cached as fresh. Newly discovered games are naturally prioritized because they have no cache row.

The genre worker owns Store failures. It may expose logs for diagnostics, but it must not change the outcome recorded for normal Steam sync.

**Alternative considered:** fetch every app during each normal poll. Rejected because the Store endpoint is per-app and large libraries would multiply poll duration and failure surface.

**Alternative considered:** fetch only when game detail opens. Rejected because Library search and collection genre choices need library-wide coverage, though a future release could prioritize the currently opened app.

### 4. Join cached genres into the shared library domain model

`GameRepository.library`, `goalGames`, and `backlog` combine the existing game/HLTB flows with the genre-cache Flow and expose `genres: List<GameGenre>` on `LibraryGame`. Missing or malformed cache data maps to an empty list without blocking the game row. Genre identity uses the Store id; label comparisons are trimmed and case-insensitive.

This keeps the three screens on one source of truth and preserves offline behavior. JSON decoding is isolated at the cache repository boundary rather than repeated in UI code.

### 5. Keep matching and selection rules pure

Library matching becomes `name contains query OR any genre label contains query`, ignoring case. A game that matches both is emitted once because filtering remains predicate-based over the existing list.

Collection Add games uses a pure predicate with these stages:

1. Existing member app ids are removed.
2. A nonblank text query must match the game name, ignoring case.
3. If genre ids are selected, the game must contain at least one selected id.

Selected genres therefore form a union (`OR`), while text and genre constraints combine by intersection (`AND`). Unknown/empty genre lists do not match an active genre filter. Filter state is transient and is not persisted with the collection.

### 6. Use compact, explicit UI affordances

Game detail renders non-clickable genre surfaces in a `FlowRow` within the existing summary. No section label or placeholder is rendered for an empty genre list.

Library retains its existing single search field and changes its label to `Search games or genres`; no permanent genre-chip strip is added.

Collection Add games adds one compact Genres control that opens a multi-select sheet/list. Selected values appear as removable wrapping chips near the text search with an explicit clear-all action. The available genre catalog is derived from the current library, de-duplicated by genre id and sorted by label. Text and selected genre ids use saveable transient UI state so adding an individual game does not reset them.

## Risks / Trade-offs

- **The Store endpoint is outside the documented Steam Web API contract** -> Isolate it behind a best-effort data source, cache aggressively, throttle requests, and never make core sync depend on it.
- **Initial genre coverage is incomplete while a large library backfills** -> Prioritize missing entries, continue bounded batches automatically, and make all UI degrade to existing behavior when genres are unknown.
- **Genre labels can change or be localized by Steam** -> Use Store ids for selection identity, request fixed English labels for the current English UI, and refresh stale rows periodically.
- **An empty genre list can mean a non-game app or unavailable metadata** -> Cache definitive empty results to prevent request loops, while omitting misleading UI placeholders.
- **Atomic JSON storage cannot support efficient SQL genre queries** -> Accept this for the initial in-memory filtering architecture; normalize later only if analytics or database-side filtering requires it.
- **Malformed cached JSON could otherwise break all library consumers** -> Decode defensively per app and treat only that row as empty while leaving the game visible.

## Migration Plan

1. Add Room schema v12 with the `game_genre_cache` table and register the v11-to-v12 additive migration.
2. Existing games receive no synthetic cache rows, so their genre state begins unknown without changing current UI or sync behavior.
3. Ship genre scheduling and cache joins; the next successful owned-games sync enqueues gradual backfill.
4. Enable the three consumers after they tolerate empty genre lists, ensuring partial backfill never blocks rendering.
5. If Store enrichment must be disabled after release, stop scheduling the worker; cached rows and all existing core data remain safe. Database rollback is forward-only, consistent with existing Room migrations.

## Open Questions

None blocking. Batch size, spacing, and freshness constants are intentionally named tuning points and may be made more conservative if device validation observes Store throttling.
