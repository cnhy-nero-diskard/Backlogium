## 1. Typed failures at the data-source boundary

- [ ] 1.1 Replace the string-formatted `error("HLTB search failed: HTTP ${response.code}")` at `ScrapingHltbDataSource.kt:166` and the empty-body error at `:168` with typed exceptions carrying the status code and enough structure to classify on
- [ ] 1.2 Define the failure taxonomy from design.md Decision 1: rotation/expiry, throttling, server error, transport, parse, cancellation
- [ ] 1.3 Confirm classification never matches on message text — that would be the same defect in a new place
- [ ] 1.4 Test: each class is produced from its corresponding condition

## 2. Scope the session re-resolution

- [ ] 2.1 Replace the catch-all recovery at `ScrapingHltbDataSource.kt:63-66` so only rotation/expiry evidence triggers `resolveSession(force = true)`
- [ ] 2.2 Confirm the narrowed trigger matches the intent already documented at `:39` — endpoint rotation or a rejected search
- [ ] 2.3 Guarantee at most one re-resolution per search, so a failing retry cannot loop
- [ ] 2.4 Test: a 500 and a timeout do **not** re-resolve; a 403 does
- [ ] 2.5 Test: a re-resolution whose retry also fails does not attempt a second re-resolution
- [ ] 2.6 Consider stopping a batch early on a parse failure, since a changed page shape fails identically for every remaining game — implement if cheap, otherwise record as a known inefficiency

## 3. Distinguish failure from no-match

- [ ] 3.1 Replace the nullable `HltbMatchState?` outcome in `HltbRepository.refreshBatch` with an explicit type covering refreshed, no-match, and failed — with failed carrying its class
- [ ] 3.2 Split the counters: `done` for attempted, `refreshed` for actually updated
- [ ] 3.3 Keep `done` advancing on every game including failures, preserving the property described at `HltbRepository.kt:111` that a caller must not read "no emissions yet" as a stalled run
- [ ] 3.4 Update every `onProgress` consumer for the new outcome type
- [ ] 3.5 Test: a batch of genuine no-matches reports them as no-match, not as failures

## 4. Honest completion reporting

- [ ] 4.1 Change `HltbRefreshWorker.kt:60-72` so `notifyComplete` receives the refreshed count rather than the attempted count
- [ ] 4.2 Make the notification wording distinguish refreshed, no-match, and failed rather than reporting one number
- [ ] 4.3 Test: a batch where all fifty lookups fail reports zero refreshed

## 5. Deliberate retry policy

- [ ] 5.1 Implement design.md Decision 3's matrix: retry on wholesale transient or throttled failure, succeed on partial progress, do not retry a wholesale parse failure
- [ ] 5.2 Start at the conservative threshold — retry only when zero games were refreshed and at least one failure was transient
- [ ] 5.3 Record the threshold and the reasoning in a comment, since widening it should require evidence rather than instinct
- [ ] 5.4 Test: each row of the matrix

## 6. Cancellation, scoped by evidence

- [ ] 6.1 Check whether `Converters.kt`'s three broad catches wrap anything suspending; if they do not, leave the file alone and record it as a false positive rather than editing on faith
- [ ] 6.2 Rethrow `CancellationException` in `ScrapingHltbDataSource.kt` (5 sites) and `HltbRepository.kt` (2 sites)
- [ ] 6.3 Rethrow in `SettingsDataStore.kt` where a broad catch wraps a suspend call
- [ ] 6.4 Handle `BackupMergeEngine.kt` (4 sites) per the sequencing note in design.md Decision 4 — if `auditfix-backup-integrity` has landed, its preflight rework already changes what these blocks should do, so fold rather than duplicate
- [ ] 6.5 Match the existing house pattern at `AchievementRepository.kt:264` and its KDoc at `:238` rather than inventing a new one
- [ ] 6.6 Test: cancellation propagates from each file changed
- [ ] 6.7 Confirm the high-risk paths already handling this correctly were not disturbed — `SteamSyncWorker.kt:111,244`, `ReconciliationWorker.kt:72`, `AchievementRepository.kt:264`

## 7. Verification and close-out

- [ ] 7.1 Run `./gradlew :gamification:test :app:testDebugUnitTest` and confirm green
- [ ] 7.2 Exercise a real HLTB batch with the network disabled and confirm no session re-resolution occurs and the notification reports zero refreshed
- [ ] 7.3 Confirm a normal batch still reports the same counts it did before, so the honest reporting did not introduce an off-by-one
- [ ] 7.4 Run `openspec validate auditfix-failure-classification`
- [ ] 7.5 Record in the commit message that the completion notification may now report fewer games than the batch size, and that this is the correction rather than a regression
