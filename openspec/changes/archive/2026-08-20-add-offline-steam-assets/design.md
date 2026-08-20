## Context

Backlogium currently gives Coil remote URL strings wherever Steam imagery is rendered. Game icons
come from `GetOwnedGames`, the player avatar comes from `GetPlayerSummaries`, achievement icon URLs
come from the stored schema, and five game-art URLs are derived from each app id by
`SteamIconMapper`. Coil's ordinary memory and disk caches make repeat viewing faster, but they are
evictable implementation caches and cannot guarantee that a whole library remains available
offline.

The download can cover thousands of URLs and hundreds of megabytes. It therefore cannot live in a
Settings coroutine or inside `SteamSyncWorker`; it must survive navigation and process recreation,
report durable progress, tolerate expected 404s, and avoid losing a previously good file when a
refresh fails. At the same time, every existing on-demand image path and ordered fallback chain must
keep working for assets that have not been downloaded.

The image endpoints are Steam-hosted CDN URLs, not additional Steam Web API metadata requests. The
job discovers its inventory entirely from locally synced data and deterministic URL mapping.

## Goals / Non-Goals

**Goals:**

- Provide repeatable, manual missing-only and refresh-all downloads for every Steam image type the
  app currently renders.
- Keep successful files in durable app-private storage rather than an evictable image cache.
- Make all current Steam image consumers transparently prefer stored files and fall back to their
  existing remote URLs.
- Expose progress and completion independently from Steam data sync.
- Preserve useful work across interruption and isolate individual missing or failed assets.

**Non-Goals:**

- Discover or download screenshots, videos, Store backgrounds, HLTB covers, or any media not
  currently rendered by Backlogium.
- Trigger metadata sync, achievement refresh, or genre enrichment to discover additional URLs.
- Automatically schedule bulk image downloads after sync.
- Replace Coil, remove on-demand loading, export image files in backups, or add cloud storage.
- Add automatic pruning or a user-facing "clear assets" action in this change.

## Decisions

### 1. Treat the download as an independent unique WorkManager job

A dedicated `SteamAssetDownloadWorker` will be enqueued only by the Settings action with a network
constraint and `requiresStorageNotLow`. It has its own unique work name, progress stream, and
completion state; it does not share the unique name, UI state, or lifecycle of `SteamSyncWorker`.
`ExistingWorkPolicy.KEEP` prevents duplicate taps from stacking whole-library jobs.

Because a large library can exceed ordinary worker execution expectations, the worker will run as
long-running foreground work with a low-importance progress notification. The Settings screen
remains the richer progress surface. Cancelling the job stops future requests while leaving every
atomically completed file valid for the next run.

Alternative considered: launch a `viewModelScope` coroutine. This would be simpler but would die
when Settings or the app process closes and could not supply a trustworthy persistent progress bar.

### 2. Build a finite inventory from current local state

At the beginning of each attempt, an inventory service will collect and deduplicate non-blank URLs
for:

- the stored player avatar;
- every stored owned-game icon;
- all five `SteamIconMapper` artwork variants for each owned app id;
- every stored achievement icon.

Each item carries a stable kind and optional app/achievement identity for diagnostics and progress
copy. The inventory does not call Steam metadata endpoints. If later syncs discover a game,
achievement, or changed URL, that item appears on the next manual missing-only or refresh-all run;
until then the existing on-demand loader remains available.

Alternative considered: call Store App Details for a broader media list. That would blur image
prefetch with metadata enrichment, add rate/shape uncertainty, and download imagery the app does not
use.

### 3. Store bytes under `filesDir/steam_assets`, indexed by Room

Downloaded bytes will live in app-private persistent files, not `cacheDir` and not Coil's disk
cache. A `steam_asset_manifest` table will key records by normalized URL (with a SHA-256-derived
filename) and retain the asset kind, relative path, byte count, content checksum, last successful
download time, last checked time, and state (`STORED` or `UNAVAILABLE`). A singleton
`steam_asset_download_state` row will retain the last completed mode, timestamp, and result counts.
The database advances from version 14 to 15 with an additive migration.

The manifest is an index, not a second copy of the image. A record is usable only when its file
exists and matches its recorded length/checksum; stale records and orphan temporary files are
treated as missing. Asset files and their manifest rows are derived data and remain outside backup
export/import.

Alternative considered: pre-warm Coil's disk cache. Coil is allowed to evict that cache while the
batch is still filling it, so completion would not mean that all successful assets remain stored.

### 4. Define modes by durable state, not Coil cache state

Before enqueue, Settings presents two explicit choices:

- **Download missing:** skip a valid `STORED` file. Also skip a recent `UNAVAILABLE` marker so known
  Steam 404/410 responses are not hammered on every run; the marker expires after 30 days and can
  then be checked again. Missing/corrupt files and transient failures are attempted.
- **Refresh all:** request every inventory URL regardless of stored or unavailable state. The run's
  start timestamp travels in WorkManager input. On worker retry, any item successfully stored or
  confirmed unavailable at or after that timestamp is skipped, so a refresh resumes rather than
  starting over.

HTTP 404 and 410 create `UNAVAILABLE`; other non-success responses and transport errors count as
transient failures. An unavailable result never fails the batch. The completion summary separates
stored/refreshed, already present, unavailable, and failed counts.

### 5. Validate and replace each file atomically

The downloader uses the existing bounded-timeout Steam `OkHttpClient` for credential-free CDN GETs,
with modest bounded concurrency (four requests) rather than one coroutine per asset. A response is
accepted only when it has a successful status, a supported image content type, non-empty bytes, and
passes an image decode-bounds check. Bytes are written to a temporary file in the asset directory,
flushed, and atomically renamed before the manifest points to them.

Refresh writes beside the old file and replaces it only after validation succeeds. A failed or
invalid response therefore leaves the last-good asset and manifest entry intact while still being
counted as a failed refresh attempt.

### 6. Publish determinate WorkManager progress and a persisted summary

The worker reports processed and total item counts plus current display label and rolling outcome
counts through WorkManager progress. Updates may be throttled to avoid a Room write for every fast
cache skip, but the final value is exact. The scheduler maps `ENQUEUED`, `RUNNING`, terminal failure,
and progress data into a dedicated `SteamAssetDownloadStatus` flow. Settings combines that flow with
manifest aggregates (stored item count and bytes) and the last-run summary.

The progress bar is determinate whenever `total > 0`; queued inventory preparation is shown as an
indeterminate pre-progress state rather than a false `0 / 0`. Leaving and reopening Settings reads
the same WorkManager-backed state. Steam sync buttons and indicators remain usable and independent.

### 7. Make local-first resolution transparent at Coil's request boundary

Install one application-wide Coil `ImageLoader` with a `SteamAssetInterceptor` ahead of normal
fetching. For recognized Steam image URLs, it asks the manifest store for a valid file and executes
the request against that file. If the local read/decode fails, the interceptor invalidates that
manifest entry and retries the original remote URL through Coil. If no stored copy exists, the
request follows its current remote path unchanged.

This central interception covers avatar, game icon, artwork, achievement thumbnails, history, Home,
and game-detail accent extraction without threading file paths through every UI model. Existing URL
keys, placeholders, and the ordered per-game fallback lists remain authoritative; the interceptor
only changes where a given URL's bytes come from.

Alternative considered: resolve files separately in each composable/view model. That duplicates
policy across every image consumer, risks missing less-obvious paths such as accent-color sampling,
and couples presentation state to filesystem details.

## Risks / Trade-offs

- **Large libraries consume substantial storage and bandwidth** → show stored count and bytes,
  require storage-not-low, use explicit confirmation/mode choice, and never trigger automatically.
- **Some deterministic Steam artwork URLs legitimately return 404** → record time-bounded
  `UNAVAILABLE` outcomes and report them separately from failures.
- **A refresh could destroy a good offline copy** → validate into a temporary file and replace
  only after success.
- **A long run can be stopped by the OS or lose connectivity** → foreground WorkManager,
  per-item commits, a stable run-start timestamp, and resume-by-manifest semantics.
- **Room lookup on every image request adds overhead** → maintain a process-local snapshot of
  valid URL-to-file entries, refreshed from the manifest flow; filesystem verification remains the
  final guard.
- **Persistent assets will not be automatically reclaimed** → expose their size now and leave
  cleanup/pruning as a deliberate follow-up rather than silently deleting requested offline data.
- **Steam may change CDN content at a stable URL** → refresh-all deliberately bypasses stored and
  negative freshness; missing-only optimizes for preserving already downloaded content.

## Migration Plan

1. Add the manifest and summary tables with the 14-to-15 Room migration; existing installations
   start with an empty manifest and no asset files.
2. Install the local-first Coil interceptor. With an empty manifest it behaves exactly like the
   current remote loader, so rollout does not require an initial bulk download.
3. Add the worker/scheduler and Settings controls. Users opt in to the first download manually.
4. If the feature must be rolled back, the prior app behavior continues to work from remote URLs;
   the additive tables and unreferenced app-private files can remain without affecting rendering.

## Open Questions

None required for the initial implementation. A later change can decide whether to add selective
asset categories, Wi-Fi-only choice, automatic orphan pruning, or a clear-downloaded-assets action.
