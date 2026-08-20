## 1. Asset persistence

- [x] 1.1 Add Steam asset kind, manifest-state, download-mode, and last-run summary models.
- [x] 1.2 Add Room entities and DAO operations for URL lookup, manifest upsert/invalidation, stored count/bytes aggregation, unavailable freshness, and the singleton completion summary.
- [x] 1.3 Advance `BacklogiumDatabase` from version 19 to 20 with an additive migration for the asset manifest and download-state tables, then expose the DAO through Hilt.
- [x] 1.4 Implement the app-private `filesDir/steam_assets` store with normalized URL hashing, temporary writes, decode-bounds/content validation, checksum metadata, and atomic replacement that preserves last-good files.
- [x] 1.5 Add unit and Room migration tests for manifest queries, integrity mismatch handling, unavailable timestamps, aggregate size/count, atomic refresh success, and failed-refresh preservation.

## 2. Inventory and download engine

- [x] 2.1 Add DAO projections for all non-blank known game, achievement, and profile image URLs needed by the inventory without loading unrelated row data.
- [x] 2.2 Implement deterministic inventory construction for avatar, game icons, all five `SteamIconMapper` artwork variants per owned game, and known achievement icons, including blank filtering and URL deduplication.
- [x] 2.3 Implement missing-only selection for valid stored files, corrupt/missing files, transient failures, and 30-day unavailable markers.
- [x] 2.4 Implement refresh-all selection and run-start resume semantics so items completed during the same WorkManager run are not downloaded again after retry.
- [x] 2.5 Implement credential-free Steam CDN downloading through the bounded-timeout OkHttp client with four-request concurrency, response/content validation, and explicit stored, skipped, unavailable, and failed outcomes.
- [x] 2.6 Persist each completed item and the exact terminal run summary while keeping individual failures isolated from successful items.
- [x] 2.7 Add focused tests for complete inventory coverage, deduplication, missing-only and refresh-all behavior, unavailable-marker expiry, HTTP outcome classification, bounded concurrency, partial failure, and resume selection.

## 3. WorkManager scheduling and progress

- [x] 3.1 Add `SteamAssetDownloadWorker` with mode and run-start input, durable per-item commits, determinate processed/total progress, rolling outcome counts, and cancellation-safe cleanup of temporary files.
- [x] 3.2 Run the download as long-running foreground work with a low-importance notification channel and current processed/total progress, adding the required Android foreground-service declarations for supported API levels.
- [x] 3.3 Add unique-work enqueue and cancel operations with network and storage-not-low constraints and `ExistingWorkPolicy.KEEP`.
- [x] 3.4 Expose a dedicated scheduler flow for queued, preparing, running, cancelled/failed, and completed asset state without changing `syncInProgress`.
- [x] 3.5 Add progress-data parsing and scheduler/worker tests covering empty inventory, duplicate enqueue, constraints, progress bounds, mixed terminal outcomes, retry resume, and cancellation preservation.

## 4. Local-first image loading

- [x] 4.1 Add a process-local URL-to-file manifest snapshot that observes Room updates and verifies files before exposing them to image requests.
- [x] 4.2 Add a Coil interceptor that serves recognized Steam URLs from valid durable files, invalidates failed local copies, and retries the original remote URL before returning an error.
- [x] 4.3 Configure the application-wide Coil `ImageLoader` with the interceptor while retaining normal memory/disk caching and the existing network loader.
- [x] 4.4 Verify every current Steam image consumer—including avatar, game icons, artwork fallbacks, achievement/history thumbnails, Home imagery, and detail accent sampling—uses the application image loader without bypassing local-first resolution.
- [x] 4.5 Add tests for local hits, remote misses, corrupt-local remote retry, offline local rendering, unchanged URL identity, and unchanged horizontal/grid fallback ordering.

## 5. Settings state and interaction

- [x] 5.1 Extend `SettingsUiState`, `SettingsActions`, and `SettingsViewModel` with the dedicated asset status, stored count/bytes, last-run summary, mode selection, enqueue, and stop actions.
- [x] 5.2 Add the separate "Offline Steam assets" Settings section with explanatory copy, stored-size summary, last-completion summary, and an empty-inventory disabled state.
- [x] 5.3 Add the download-mode chooser for "Download missing assets" and "Refresh all assets", ensuring dismissal enqueues nothing.
- [x] 5.4 Add queued/preparing presentation and a determinate dedicated progress bar with processed/total and outcome text while work runs.
- [x] 5.5 Add a stop control that cancels only asset work and restore the download action after every terminal state.
- [x] 5.6 Add Compose tests for section placement, empty and populated summaries, mode selection/dismissal, progress rendering, overlap with active Steam sync, stop behavior, and terminal-state recovery.

## 6. Integration safeguards

- [x] 6.1 Reconcile stale manifest rows and orphan temporary files without deleting valid requested assets, and ensure missing files become eligible on the next missing-only run.
- [x] 6.2 Confirm normal Steam sync, reconciliation, genre enrichment, HLTB refresh, and backup export/import neither enqueue nor serialize the offline asset store.
- [x] 6.3 Confirm a later normal sync can add or change known URLs and that the next manual missing-only run discovers them without requiring an app restart.
- [x] 6.4 Confirm on-the-fly Steam image loading and themed placeholders continue to work before any bulk download and for every asset absent from durable storage.

## 7. Validation

- [x] 7.1 Run `:app:compileDebugKotlin`, app unit tests, the Room migration suite, and focused worker/scheduler/image-loader tests offline where dependencies are cached.
- [x] 7.2 Run Settings instrumentation tests on an available emulator or device and verify the progress UI across navigation away, process recreation, concurrent Steam sync, and cancellation.
- [x] 7.3 Manually verify missing-only downloads the full known Steam inventory, a second missing-only run skips valid files, and refresh-all re-requests existing and unavailable assets.
- [x] 7.4 Manually verify airplane-mode rendering from durable files plus on-the-fly/fallback behavior for absent, corrupt, and legitimately unavailable assets.
- [x] 7.5 Manually verify partial network failure and low-storage behavior preserve prior files, expose accurate counts, and resume without repeating completed refresh work.
- [x] 7.6 Run strict OpenSpec validation and `git diff --check`, then record automated and device-verification evidence task by task.

## Verification evidence

- `:app:compileDebugKotlin --offline --no-daemon` and `:app:installDebug --offline --no-daemon` passed.
- `:app:connectedDebugAndroidTest --offline --no-daemon` passed: 14 tests, 0 failures/errors, API 35 emulator.
- `openspec validate add-offline-steam-assets --strict` and `git diff --check` passed.
- Manual emulator verification confirmed missing-only, refresh-all, cancellation, navigation/process recreation, concurrent sync, offline rendering, fallback behavior, network failure, and low-storage behavior.
- `:app:compileDebugKotlin --offline --no-daemon` re-run after adding the test suites below: `BUILD SUCCESSFUL`.
- `:app:testDebugUnitTest --offline --no-daemon` (full app module) passed with the new suites included: `SteamAssetDaoTest` (15), `SteamAssetStoreTest` (14), `SteamAssetRepositoryTest` (9), `SteamAssetInterceptorTest` (11), `SteamAssetDownloadWorkerTest` (5), `SteamAssetSchedulerTest` (11) — 65 new tests, 0 failures/errors, no regressions in the rest of the suite.
- `:app:connectedDebugAndroidTest --offline --no-daemon` filtered to `MigrationTest` (adds `v19ToV20_addsSteamAssetTablesAndLeavesExistingDataUntouched`) and the new `OfflineSteamAssetsCardTest` (11 Compose tests) on `Medium_Phone_API_35(AVD)`: 18/18 passed, 0 skipped/failed. `OfflineSteamAssetsCardTest` covers section placement, empty/populated summaries, mode selection/dismissal, progress rendering, overlap with active Steam sync, stop behavior, and terminal-state recovery directly on `OfflineSteamAssetsCard`; navigation-away and process-recreation survival were already covered by the 7.4/7.5 manual emulator verification above (WorkManager-backed state, not composable state, so scripting a Compose-level recreation test would not add coverage beyond what's already exercised manually).
- Review regressions passed: cold-start resolver fallback to Room before the initial snapshot, blank-icon game appId-derived artwork inventory, and run-start stale-manifest/orphan-file reconciliation preserving current stored assets.
- Post-review full app unit tests passed (67 focused Steam-asset tests, 0 failures/errors); the connected-test rerun was attempted but blocked by the current environment reporting no connected ADB devices.
