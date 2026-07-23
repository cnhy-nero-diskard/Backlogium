## 1. Local cache (Room)

- [x] 1.1 Add `HltbData` entity (`appId` PK/FK, `hltbId: Long?`, four `*Minutes: Int?` fields, `fetchedAt: Long`, `matchStatus` enum, `candidatesJson: String?`)
- [x] 1.2 Add `HltbDataDao` (upsert, get by appId, observe/get all, query `NEEDS_REVIEW` rows, list appIds with stale/missing data for a freshness cutoff)
- [x] 1.3 Register the entity in `BacklogiumDatabase`, bump schema version, add a migration that creates the `hltb_data` table (additive, no backfill)
- [x] 1.4 Provide the DAO via the Hilt `DatabaseModule`

## 2. Data source seam + client-side scraper

- [x] 2.1 Define `HltbDataSource` interface and `HltbCandidate` model (hltbId, name, four completion lengths, confidence)
- [x] 2.2 Add HLTB DTOs (kotlinx.serialization) for the search response and the init token/key/val response
- [x] 2.3 Implement `ScrapingHltbDataSource`: fetch homepage, locate `_app-*.js` bundle, regex the POST `fetch` endpoint path (with hard-coded fallback), GET `{endpoint}/init` for token/key/val
- [x] 2.4 Issue the search POST with browser `User-Agent`/`Referer`/`Origin` + auth headers; map the response to `HltbCandidate`s; resolve endpoint+token at call time (never persist)
- [x] 2.5 Wire an HLTB `Retrofit`/`OkHttp` (or raw OkHttp) client for the `howlongtobeat.com` base URL in `NetworkModule`; bind `HltbDataSource` to the scraping impl via Hilt
- [x] 2.6 Unit-test endpoint-path extraction and candidate mapping against captured fixtures

## 3. Matching + repository

- [x] 3.1 Implement the confidence heuristic and classification (single confident → `RESOLVED`; multiple/low → `NEEDS_REVIEW` with candidates; none → `UNMATCHED`)
- [x] 3.2 Add `HltbRepository`: `fetchForGame(appId, name)` (cache-first), `resolveMatch(appId, chosen)`, freshness-gated `staleOrMissing(cutoff)`, observe review list
- [x] 3.3 Add a `FRESHNESS_WINDOW` constant (default 2 months) and reuse endpoint+token across a batch (throttle helper with fixed inter-request delay)
- [x] 3.4 Unit-test classification thresholds and freshness-gate selection

## 4. Triggers

- [x] 4.1 Per-game: update `GameRepository.tagGoal` to fetch HLTB data cache-first on goal tag and populate the goal completion length (Main Story)
- [x] 4.2 Batch: add `HltbRefreshWorker` (WorkManager one-shot `hltb_refresh_now`) that sweeps `staleOrMissing` games (or all when forced), resolving endpoint+token once and throttling requests
- [x] 4.3 Report progress + completion notification from the worker; add an enqueue helper in `SyncScheduler` with a `force` flag
- [x] 4.4 Never overwrite/clear cached data on lookup failure; surface errors without discarding last-good data

## 5. UI

- [x] 5.1 Restore goal progress on the Library screen: show playtime-vs-Main-Story progress when a length exists, nothing when it does not
- [x] 5.2 Add a "Refresh HLTB library" control (with force option) that enqueues the worker and reflects running/complete state
- [x] 5.3 Add the match-review surface: list `NEEDS_REVIEW` games with their candidates; selecting one calls `resolveMatch` and clears the flag; empty state when nothing to review
- [x] 5.4 Wire navigation/entry point to the review surface and expose review count/state via `LibraryViewModel`

## 6. Verification

- [x] 6.1 Verify goal progress renders for a matched game and is absent for an unmatched/pending one
- [x] 6.2 Verify a batch run skips fresh games, refreshes stale/missing, and force re-fetches all
- [x] 6.3 Verify ambiguous games land in review and manual selection resolves them end-to-end
- [x] 6.4 Confirm the gamification/achievement consumers receive the Completionist metric from the cache
- [x] 6.5 Run `openspec validate add-hltb-integration --strict` and the app's unit tests

## 7. Per-game status + single-game refresh

- [x] 7.1 Track transient per-game lookup state (in-progress / failed) in `LibraryViewModel`, layered over the persisted match status
- [x] 7.2 Add a `refreshGame(appId, name)` action that force-fetches one game and surfaces success/failure without clearing cached data on failure
- [x] 7.3 Show each game's HLTB state on the Library rows and expose a "Refresh HowLongToBeat" control in the goal dialog
