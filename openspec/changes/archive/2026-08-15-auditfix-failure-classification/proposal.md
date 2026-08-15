## Why

Three findings share one habit: treating unlike failures alike, and reporting outcomes the
code does not actually know to be true.

- **HLTB search treats every throwable as a stale session.** `ScrapingHltbDataSource.kt:63-66`
  wraps `postSearch` in `runCatching` and, on *any* failure, calls `resolveSession(force = true)`
  and retries. The intended trigger is narrow and documented at `:39` — "re-resolved when that
  window lapses or a search is rejected (HTTP 403 / rotation)". In practice a DNS failure, a
  timeout, a 500, a parse error, or throttling all trigger a full session re-resolution:
  homepage fetch, JS chunk scan for the rotating endpoint, token extraction. A transient
  network blip becomes three extra requests to a site being scraped on sufferance, and the
  retry then fails the same way.
- **A batch of failures is reported as a batch of successes.** `HltbRepository.refreshBatch`
  advances its `done` counter for every game including lookup failures, which surface as a
  `null` outcome. `HltbRefreshWorker.kt:60-72` assigns `completed = done` and passes it to
  `notifyComplete`. Fifty consecutive failures produce a notification saying fifty games were
  refreshed. The same swallowing also denies WorkManager the failure it would otherwise back
  off and retry on — the worker returns `Result.success()` having accomplished nothing.
- **Cancellation is swallowed in some paths.** Narrower than the audit states, and worth
  correcting: the high-risk paths already handle this properly — `SteamSyncWorker.kt:111,244`,
  `ReconciliationWorker.kt:72`, and `AchievementRepository.kt:264` all rethrow
  `CancellationException`, with KDoc at `AchievementRepository.kt:65,238` explaining why. The
  residual exposure is specific: `ScrapingHltbDataSource` (five broad catches),
  `BackupMergeEngine` (four), `HltbRepository` (two), `SettingsDataStore` (three), and
  `Converters` (three), none of which import `CancellationException`. The fix is scoped to
  those files, not a sweep of the forty files with a broad catch.

## What Changes

- **HLTB failures are classified before being responded to.** Only evidence of endpoint
  rotation or token expiry — the documented triggers — causes a session re-resolution.
  Transport errors, server errors, throttling, and parse failures are handled as themselves,
  without amplifying load against a scraped endpoint.
- **Batch progress distinguishes refreshed from failed.** The worker reports what actually
  happened, and a batch that failed wholesale is treated as a failure rather than reported as
  success.
- **Cancellation is rethrown in the identified files.** Scoped by evidence, not swept.
- **Retry behaviour becomes deliberate.** A batch whose lookups all failed for transport
  reasons is a retry candidate; one that completed with genuine no-match results is not. The
  current code cannot distinguish these because both arrive as `null`.

## Capabilities

### Modified Capabilities

- `hltb-data`: require that a session re-resolution is triggered only by evidence of rotation
  or expiry, and that batch outcomes reported to the user and to the scheduler reflect what
  actually happened.

## Impact

| Path | Change |
|---|---|
| `data/hltb/ScrapingHltbDataSource.kt` | failure classification before session recovery; rethrow cancellation |
| `data/repo/HltbRepository.kt` | outcome distinguishes failure from no-match; rethrow cancellation |
| `work/HltbRefreshWorker.kt` | honest completion reporting; deliberate retry |
| `data/backup/BackupMergeEngine.kt` | rethrow cancellation |
| `data/local/SettingsDataStore.kt`, `data/local/Converters.kt` | rethrow cancellation where a broad catch wraps a suspend call |

**A visible behaviour change**: the completion notification will sometimes report fewer
refreshed games than the batch size, where today it always reports the full count. This looks
like a regression and is the opposite — the number was previously wrong whenever anything
failed.

**Interacts with `auditfix-backup-integrity`**, which also touches `BackupMergeEngine`'s catch
blocks. Preflight validation changes what those blocks should do, so if that change lands
first, the cancellation fix there becomes part of a broader rework of the same code. Sequence
them; do not do the same file twice.

**No dependency on the other changes.** Small, self-contained, and safe to land at any point.

**Not addressed here**: the HLTB scraping approach itself, retry/backoff policy for the
scraped endpoint beyond not amplifying failures, and the remaining broad `catch (e: Exception)`
sites in files where no suspend call is wrapped — a broad catch is not by itself a defect.
