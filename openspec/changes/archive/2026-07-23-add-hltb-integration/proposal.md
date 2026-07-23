## Why

The app was built in anticipation of HowLongToBeat (HLTB) completion lengths: `Game.targetMinutes` is dormant, the Library's goal progress bar was removed until an HLTB length exists, and the gamification/achievement engines already expect a `completionistAverageMinutes` per game that nothing currently supplies. HLTB has no official API, but actively-maintained community wrappers show a viable (if fragile) way to read it. This change fills that pre-cut seam so goals and XP can finally use real completion lengths.

## What Changes

- Add an HLTB data source that reads completion lengths client-side (no backend), behind an interface so a server-side proxy can replace it later without touching consumers.
- Cache all four HLTB metrics per game (Main Story, Main+Extras, Completionist, All-Styles) in a new local table, keyed by Steam `appId`, with a `fetchedAt` freshness timestamp and a resolved `hltbId`.
- **Per-game trigger:** tagging a game as a goal fetches its HLTB data on demand (cache-first) and populates the goal's completion length.
- **Batch trigger:** a manual "Refresh HLTB library" action sweeps the library via a WorkManager one-shot, skipping games whose cached data is younger than a freshness window (default 2 months) unless a "force re-fetch all" option is chosen.
- Match Steam game names to HLTB entries with a confidence heuristic: a single strong match resolves silently; ambiguous games are flagged `NEEDS_REVIEW` with their candidates persisted.
- Add a deferred **match-review screen** where the user confirms the correct HLTB entry for flagged games after a batch run.
- Restore goal progress on the Library screen, now measured against the HLTB-sourced completion length.

## Capabilities

### New Capabilities
- `hltb-data`: fetching, name-matching, caching, and freshness of HowLongToBeat completion-length data — the client-side data source and its swappable seam, the per-game and batch triggers, the match-confidence/flagging state machine, and the local cache with its freshness gate.

### Modified Capabilities
- `app-ui`: the Library screen restores goal progress against an HLTB-sourced completion length, and gains a manual "Refresh HLTB library" trigger and a match-review surface for confirming ambiguous matches.

## Impact

- **New code:** `HltbDataSource` interface + client-side scraping implementation, an HLTB DTO/mapper, a Room entity + DAO for the cache table (Room schema version bump/migration), an HLTB repository, a WorkManager worker for the batch sweep, and Library/review UI + ViewModel wiring.
- **Modified code:** `GameRepository.tagGoal` (populate length on goal tag), `LibraryScreen`/`LibraryViewModel` (goal progress + refresh trigger + review entry point), navigation for the review screen. `SteamSyncWorker` is untouched — the separate cache table keeps HLTB writes off the Steam-sync path.
- **Dependencies:** reuses the existing Retrofit/OkHttp/kotlinx.serialization + WorkManager stack; no new API key.
- **Consumers unblocked:** goal progress, and the `completionistAverageMinutes` input the gamification/achievement engines already expect.
- **Risk:** HLTB actively rotates its scrape endpoint and requires an init-token handshake; the client-side reader can break until the app ships a fix. The `HltbDataSource` seam and freshness-driven low request volume mitigate this.
