## Why

Backlogium currently knows a game's name and playtime but not what kind of game it is, leaving game detail, Library discovery, and collection building disconnected from a basic way players understand their backlog. Adding cached Steam Store genres creates one reusable metadata capability that improves all three surfaces without changing collection membership semantics.

## What Changes

- Acquire broad Steam Store genre metadata for owned games through a best-effort, separately throttled enrichment path and retain last-known values locally.
- Display known genres as compact, wrapping tiles in the game-detail summary.
- Extend Library search so one query can match a game name or any of its genres.
- Add a compact multi-select genre filter to the collection editor's Add games pool.
- Define additive genre selection as union matching: a game remains eligible when it has any selected genre; the text query must also match when present.
- Keep genre filtering non-destructive: filters never add games automatically, remain active while individual games are added, and can be cleared explicitly.

## Capabilities

### New Capabilities

- `game-genres`: Best-effort acquisition, local caching, refresh, normalization, and availability behavior for per-game Steam Store genres.

### Modified Capabilities

- `app-ui`: Display genres on game detail, match genres in Library search, and filter the collection Add games pool by multiple selected genres.

## Impact

- **Persistence:** Room schema and migration for cached per-game genre metadata and refresh state.
- **Remote data:** A Steam Store metadata client distinct from the authenticated Steam Web API polling path; no new user credentials.
- **Repository/domain:** The shared library-game model exposes normalized genres to all three consumers.
- **UI:** `GameDetailViewModel`/`GameDetailScreen`, `LibraryViewModel`/`LibraryScreen`, and `CollectionScreen` collection editing.
- **Sync behavior:** Genre enrichment is missing/stale-only, bounded, retryable, and must not delay or fail normal owned-game/playtime sync.
- **Backup:** No backup-format change; genres are recoverable Steam-owned cache data rather than user-authored state.
