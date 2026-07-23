## Context

Backlogium is a client-only Android app (Compose + Room + Hilt + WorkManager) that reads the Steam Web API directly with an embedded key and has no backend. The codebase was designed in anticipation of HowLongToBeat (HLTB): `Game.targetMinutes` is dormant, the Library's goal progress bar was removed pending an HLTB length (see the archived `restyle-fixes` change), and the `add-gamification-engine` / `add-achievement-xp` changes both consume a per-game `completionistAverageMinutes` that nothing supplies — with the explicit boundary that the engine "only consumes an already-fetched value; it does not query HowLongToBeat itself."

HLTB has no official API. Every community wrapper (JS `ckatzorke`, the actively-maintained Python `ScrappyCocco`, .NET, Go, PHP) performs the same fragile scrape, and HLTB actively defends against it: the search endpoint path rotates and a per-session init-token handshake is required. This design brings that reader into the app while containing its fragility.

## Goals / Non-Goals

**Goals:**
- Supply per-game HLTB completion lengths to the already-cut consumer seam (goal progress, gamification input).
- Two triggers: on-demand per-game fetch when tagging a goal; a manual, throttled, freshness-gated batch sweep of the library.
- Cache all four HLTB metrics per game with a fetch timestamp; make batch runs cheap by skipping fresh data.
- Isolate HLTB fetching behind an interface so a future proxy is a drop-in replacement.
- Keep ambiguous name matches out of the batch's critical path via deferred user review.

**Non-Goals:**
- Any server-side / proxy implementation (explicitly deferred; the seam is provided, not the proxy).
- Changing how the gamification/achievement engines compute XP — this only feeds them data.
- Touching the Steam-sync write path or `SteamSyncWorker`.
- Automatic background scheduling of the batch refresh — it is manual only.

## Decisions

### Separate `hltb_data` table, not columns on `Game`
Store HLTB data in a new Room entity keyed by `appId` (FK to `games`), rather than extending `Game`.
- **Fields:** `appId` (PK), `hltbId: Long?`, `mainStoryMinutes/mainExtraMinutes/completionistMinutes/allStylesMinutes: Int?`, `fetchedAt: Long`, `matchStatus: enum(RESOLVED, NEEDS_REVIEW, UNMATCHED)`, `candidatesJson: String?`.
- **Why:** keeps HLTB writes entirely off the Steam-sync path (`SteamSyncWorker` already has to carefully preserve `isGoal`/`targetMinutes` on every upsert — coupling a second async writer to the same row invites lost-update bugs). Clean source isolation is the future-proof choice. Requires a Room schema version bump + migration that creates the table (no data migration).
- **Alternative considered:** columns on `Game` — simplest, but couples the two writers and bloats the sync upsert.

### Store all four metrics; consumers select
The cache retains Main Story, Main+Extras, Completionist, and All-Styles. Goal progress uses **Main Story** ("beat it"); the gamification engine uses **Completionist average**. Storing all four means changing which metric feeds a feature never forces a re-fetch.

### `HltbDataSource` interface as the seam
```
interface HltbDataSource { suspend fun search(name: String): List<HltbCandidate> }
  └─ ScrapingHltbDataSource   ← this change (client-side)
  └─ ProxyHltbDataSource      ← future, drop-in
```
The scraping implementation encapsulates the full dance: GET homepage → locate `_app-*.js` bundle → regex the `fetch(POST …)` call for the current endpoint path → GET `{endpoint}/init` for the token/key/val → POST the search with browser `User-Agent`/`Referer`/`Origin` and the auth headers. Endpoint + token are resolved at call time and never persisted; a hard-coded fallback path is used only if extraction fails. Repositories and UI depend only on the interface.

### Freshness gate drives batch volume (the primary rate-limit defense)
`fetchedAt` age controls the batch: `age < FRESHNESS_WINDOW` (default 2 months, a const) → skip; else re-fetch. A "force re-fetch all" flag ignores age (the testing/manual case). Because most sweeps then touch only new/stale games, request volume stays low. On top of that, the batch resolves endpoint + init-token **once per run** and spaces requests with a fixed delay.

### Batch runs as a WorkManager one-shot
Mirror `steam_sync_now` (`hltb_refresh_now`): survives the screen closing, reports progress, notifies on completion — appropriate for a potentially long throttled sweep. The per-game goal fetch stays a plain `viewModelScope` coroutine (single fast call, user watching).

### Deferred match review
A single candidate above the confidence threshold → `RESOLVED` silently. Multiple plausible / none confident → `NEEDS_REVIEW`, candidates persisted to `candidatesJson`. Zero results → `UNMATCHED`. The batch never blocks on a prompt. A separate review screen lists `NEEDS_REVIEW` games; selecting a candidate writes its id + lengths and flips to `RESOLVED`.

## Risks / Trade-offs

- **HLTB rotates its endpoint / changes the init-token flow** → the client-side reader breaks for all installs until an app update ships. Mitigation: resolve endpoint + token dynamically per call (not hard-coded); isolate everything behind `HltbDataSource` so a proxy fix or logic patch is contained; keep request volume low so the reader is less likely to be actively targeted.
- **Single device IP rate-limited/banned during a large sweep** → freshness-skip keeps typical runs small; endpoint/token reused once per run; fixed inter-request delay; force-all is opt-in.
- **Wrong automatic match assigns a bad completion length** → confidence threshold errs toward `NEEDS_REVIEW`; user can re-resolve any game via the review screen; all four metrics stored so a corrected match needs no re-fetch of consumers.
- **Scraping is against HLTB's terms and inherently brittle** → accepted for a personal/hobby client; the seam preserves the option to move to a compliant/robust proxy later without rework.
- **Room migration risk** → the migration only adds a table; no existing data is altered.

## Migration Plan

1. Bump the Room schema version and add a migration creating `hltb_data` (additive; no backfill).
2. Ship the reader dormant behind the two explicit triggers — nothing fetches until the user tags a goal or runs a refresh, so rollout carries no automatic load.
3. Rollback: the feature is additive and trigger-gated; disabling the UI entry points neutralizes it, and the table can be dropped in a later migration if abandoned.

## Open Questions

- Exact confidence heuristic (normalized string distance vs. HLTB's own relevance ordering) and its threshold — to be tuned during implementation against real Steam names.
- Fixed inter-request delay value for the sweep — pick a conservative default and revisit if throttling is observed.
- Whether the review surface is a distinct screen/destination or a Library sub-section — resolve during UI wiring; the spec is agnostic.
