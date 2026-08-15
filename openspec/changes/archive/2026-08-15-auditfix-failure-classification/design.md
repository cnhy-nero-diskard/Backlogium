# Design

## Context

```
  search(name)
    │
    ├─ postSearch(terms, resolveSession(force = false))
    │       │
    │       └─ ANY throwable ──▶ resolveSession(force = true) ──▶ postSearch again
    │                              │
    │                              ├─ fetch homepage
    │                              ├─ scan JS chunks for rotating endpoint
    │                              └─ extract token
    │
    └─ a DNS failure now costs 3 extra requests and fails identically
```

The recovery is correct for its intended trigger and is being fired by everything. The
comment at `ScrapingHltbDataSource.kt:39` already names the intended trigger precisely —
"re-resolved when that window lapses or a search is rejected (HTTP 403 / rotation)". The code
just does not check.

## Decision 1: Classify, then respond

Introduce an explicit failure taxonomy at the data-source boundary:

| Class | Evidence | Response |
|---|---|---|
| Rotation / expiry | HTTP 403, 401, or a rejection shape indicating a stale endpoint or token | re-resolve session once, retry once |
| Throttling | HTTP 429, or 503 with a retry hint | do not re-resolve; surface for backoff |
| Server error | HTTP 5xx | do not re-resolve; surface as transient |
| Transport | timeout, connection failure, DNS | do not re-resolve; surface as transient |
| Parse | successful response, unusable body | do not re-resolve; surface as permanent for this input |
| Cancellation | `CancellationException` | rethrow immediately |

`postSearch` already raises HTTP status as `error("HLTB search failed: HTTP ${response.code}")`
at `:166` and empty bodies at `:168` — string-formatted, so the status code is present but only
recoverable by parsing the message. **Give these typed exceptions carrying the status code.**
Classifying by matching on message text would be a second defect of the same kind as the first.

**Parse failures deserve a note.** A successful HTTP response with an unusable body most often
means the scraped page's shape changed — which is a *permanent* failure for every subsequent
lookup, not a transient one. Retrying it across a fifty-game batch produces fifty identical
failures. If parse failures can be detected as such, a batch should stop early rather than
grinding through. Worth doing if cheap; not worth blocking this change on.

**Rejected: retry once on any failure without re-resolving.** Simpler, and it keeps a retry
where a retry is pointless (a parse failure will parse identically) while still doubling load
against a scraped endpoint during an outage.

## Decision 2: Separate "failed" from "no match"

`refreshBatch`'s `onProgress` passes `outcome: HltbMatchState?`, where `null` means the lookup
failed. Two different meanings are collapsed:

- **failed** — we do not know whether this game has HLTB data
- **no match** — we asked, and there is genuinely no match

Both arrive as `null` today, which is why the counter cannot tell them apart and why the
notification cannot be honest.

**Chosen**: make the outcome an explicit type distinguishing success, no-match, and failure —
with failure carrying its class from Decision 1. `null` stops meaning anything.

The counter then splits: `done` (attempted, drives the progress bar, must still reach `total`
so a progress indicator terminates) and `refreshed` (actually updated, drives the
notification). The audit's finding is precisely that one number was doing both jobs.

**Note the existing comment at `HltbRepository.kt:111`** — "a caller rendering progress must
not read 'no emissions yet' as a stalled run." Whatever changes here, `done` must keep
advancing on every game including failures, or that property breaks. The bug is not that
failures advance the progress counter; it is that the progress counter is also reported as a
success count.

## Decision 3: Retry policy

`HltbRefreshWorker` currently returns `Result.success()` regardless, so WorkManager's backoff
never engages.

| Batch outcome | Result |
|---|---|
| All or mostly transient failures | `Result.retry()` — the condition is likely temporary |
| Mixed, some refreshed | `Result.success()` — progress was made; the next scheduled pass picks up the rest |
| All parse failures | `Result.success()` — retrying will fail identically; the scraper needs fixing |
| All throttled | `Result.retry()` — this is exactly what backoff is for |
| Cancelled | rethrow; WorkManager owns it |

**The threshold between "mostly transient" and "mixed" needs a number**, and the number wants
one piece of information this design does not have: how often a partial-failure batch occurs
normally. Start conservative — retry only when *zero* games were refreshed and at least one
failure was transient — and widen only if evidence justifies it. An over-eager retry against a
scraped endpoint is worse than a delayed refresh.

## Decision 4: Cancellation, scoped by evidence

The audit's sweeping version is wrong and following it would mean editing forty files. The
actual list, established by checking which files hold broad catches *and* lack a
`CancellationException` import:

| File | Broad catches | Action |
|---|---|---|
| `ScrapingHltbDataSource.kt` | 5 | rethrow; part of Decision 1's taxonomy anyway |
| `BackupMergeEngine.kt` | 4 | rethrow — but see the sequencing note below |
| `HltbRepository.kt` | 2 | rethrow |
| `SettingsDataStore.kt` | 3 | rethrow where a suspend call is wrapped |
| `Converters.kt` | 3 | likely no change — Room type converters are not suspend functions |

**`Converters.kt` is probably a false positive** and worth checking rather than editing on
faith. A broad catch around non-suspending serialization code cannot swallow a
`CancellationException` that was never thrown through it. Verify before touching.

**The pattern to follow already exists in this codebase** — `AchievementRepository.kt:264` and
its KDoc at `:238` ("`CancellationException` is always rethrown so callers stop promptly").
Match it rather than inventing a house style.

**Sequencing with `auditfix-backup-integrity`**: that change adds preflight validation and a
transaction to `BackupMergeEngine`, which changes what its catch blocks should do at all — with
validation ahead of the merge, a caught exception means a preflight bug. If it lands first, the
cancellation fix there folds into that rework. If this lands first, expect that change to
revisit the same blocks. Either order works; doing both independently means touching the same
five lines twice.

## Testing strategy

- each failure class maps to its intended response; specifically, a 500 and a timeout do **not**
  trigger a session re-resolution, and a 403 does
- a session re-resolution happens at most once per search
- `CancellationException` propagates from each identified file rather than being classified
- a batch where every lookup fails reports zero refreshed, not `total`
- a batch with genuine no-match results reports those as no-match, not as failures
- `done` still advances on every game including failures, preserving the progress property at
  `HltbRepository.kt:111`
- the retry decision matrix from Decision 3, case by case
- typed exceptions carry the HTTP status, and classification never matches on message text

## What this change deliberately does not do

- Does not change the HLTB scraping approach or the endpoint-resolution mechanism itself.
- Does not add a general retry/backoff layer beyond not amplifying failures.
- Does not touch broad catches in files where nothing suspending is wrapped — a broad catch is
  not by itself a defect.
- Does not tune the retry threshold beyond a conservative starting point.
