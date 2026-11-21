## Context

See `proposal.md` for motivation and `specs/hltb-dataset-refresh/spec.md` for behavior. The inspected checkout is `fix/stabilize-first-run-setup` at `0045f4aa`; this planning work does not depend on its setup implementation.

`tools/hltb-dataset/merge.mjs` exports validation, canonical serialization, and deterministic merging. Schema v1 contains one global `gatheredAt`, no row timestamps, and no game names. A redundant merge preserves all bytes. The dataset CI currently runs only `merge.test.mjs` before validating the canonical file.

The Android `SteamApi` supplies a reference for owned-library requests; `HltbGamePageParser` and its page fixtures provide examples of direct-page structured completion values and requested-ID checks. These are source/fixture evidence, not verification of today's upstream payload.

## Goals / Non-Goals

**Goals:** Establish a small CLI/report contract that can be validated independently and consumed by change `2d`; isolate private targeting state from canonical public data; bound the work of each invocation.

**Non-Goals:** Automatic mapping guesses, a full catalog crawl, scheduled collection, Android changes, dataset schema migration, Git publication, and a guarantee of per-row freshness in the published schema-v1 dataset.

## Decisions

### 1. Add a dependency-free Node 22 CLI with injected network and clock boundaries

Use `refresh.mjs` as the entry point, with small modules for targeting, direct-page parsing, checkpoints, and reporting. Import the existing merger rather than duplicating its rules. Keep network/clock/file boundaries replaceable so fixtures can exercise failures without actual Steam credentials. Adding a third-party scraper would introduce a second parser/format authority and is unnecessary for reviewed-ID direct reads.

Planned command contract:

```text
node tools/hltb-dataset/refresh.mjs --steam-id <id64> [--steam-id <id64> ...]
  [--mode missing-only|stale|refresh-all] [--max-age-days <positive-number>]
  [--app-id <id> ...] [--reviewed-mappings <schema-v1-contribution>]
  [--dry-run] [--resume <run-id>] [--state-dir <private-directory>]
  [--output <candidate-file>] [--report <sanitized-report-file>]
  [--max-requests <positive-integer>] [--max-run-seconds <positive-integer>]
```

The Steam key comes only from `STEAM_WEB_API_KEY`. IDs are strings, validated without unsafe numeric coercion. Default mode is missing-only; stale requires an explicit positive age threshold. Default private outputs/checkpoints live under `tools/hltb-dataset/.local/`, added to `.gitignore` during implementation. Reject candidate/report paths aliasing the canonical source, reviewed input, or checkpoint files. A dry run prints a sanitized plan and can write an explicitly requested report, but creates no candidate, checkpoint, or freshness mutation.

Exit `0` means complete execution or a clean plan, `2` means a usable partial execution/partial plan, and `1` means blocked or invalid execution with no publishable candidate. Consumers also check the report outcome; exit codes alone do not authorize publication.

### 2. Freeze library scope before selecting HLTB work

Resolve each supplied SteamID using the owned-library API request shape already used by the app. Validate response structure; distinguish an explicit readable zero-game result from missing/unavailable data. Deduplicate IDs, intersect optional app restrictions, then overlay reviewed mappings restricted to that intersection. Validate contributions before network work and reject correspondence conflicts globally. Contribution length values are not treated as fresh observations by this CLI: the reviewed input admits mappings, and the CLI fetches the corresponding lengths itself.

Persist the resulting snapshot and a hash of mode, threshold, target IDs, mapping inputs, baseline bytes, and state format. A resumed run keeps that snapshot; a new run resolves current ownership again. Using all canonical mappings as fallback would conceal unreadable library scope and is prohibited.

### 3. Fetch direct reviewed entries under a finite budget

Start with direct `/game/<hltbId>` structured page reads, borrowing validated examples from the Android fixtures. During implementation, verify a small live sample before finalizing the parser. Require the requested entry ID, recognized structure, and valid completion fields; reject redirected identities and challenge/error pages. Convert valid positive seconds using documented nearest-minute rounding; represent absent/zero styles as null and reject negative, malformed, or excessive known values. Handle valid partial unknown styles distinctly from a broken payload. An all-null result is a no-lengths outcome that does not remove a base tuple.

Default to one request in flight, at least one second between starts, a 15-second request timeout, at most two retries after the first attempt, 500 total requests, and a 900-second total run budget. Both Steam and HLTB attempts count against the run budget. Retry transient transport/5xx/429 failures with capped exponential backoff and jitter. Respect a valid Retry-After delay; if it exceeds the remaining budget, stop and report exhaustion. Do not circumvent access-denied or challenge responses. Inject clock/sleep/randomness for deterministic boundary tests.

### 4. Maintain private per-entry observation history; preserve schema-v1 merge semantics

Key successful-fetch evidence by HLTB ID, reviewed mapping identity, and observed/canonical value fingerprint. Advance its last-success time only after a validated usable observation, including an unchanged one. Failures and no-lengths outcomes retain previous successful evidence. Treat absent or incompatible evidence as unknown/stale; never infer entry freshness from canonical `gatheredAt`.

Save each successful observation with its actual gather time. Construct schema-v1 contributions containing only successful length observations plus eligible reviewed new mappings, grouping observations by their original timestamp as needed. A new mapping with no usable lengths can be admitted as correspondence-only input, using its explicit reviewed input's timestamp; the entry remains missing lengths. Merge all inputs against the frozen baseline once, so `datasetVersion` advances at most once. Do not put retained failed length tuples into fresh contributions. Never retimestamp old observations during resume.

**Compatibility limitation:** The final canonical dataset still has a single global timestamp. Existing consumers can assign that age to all imported rows, including untouched ones. This change guarantees truthful per-entry evidence in the tool's private history and public report; it cannot encode per-row ages in schema v1. Preserve the existing global merge rule, document this limitation explicitly, and never report a partial refresh as freshness for every canonical row. Accurate per-row ages for Android consumers would require a separately authorized format/consumer change.

### 5. Checkpoint atomically and finalize candidate/report as a bound pair

Keep private plan/checkpoints and per-entry history separate from the allowlisted public report. Store no Steam key in either. Checkpoint completed observations after each entry using temporary-file replacement. On resume, reject changed baseline, options, mappings, or state version; no implicit rebase. A new run can reuse applicable freshness evidence but does not reuse stale candidate metadata.

Before final output, reread and verify the canonical baseline hash, run the existing validator/serializer, and atomically write a separate candidate plus a report bound to its hash. Final publication consumers recheck both hashes. A crash leaving only one file is recoverable from the checkpoint and is not a valid candidate/report pair. Blocking conflicts and invalid source data produce no candidate.

### 6. Define reportVersion 1 for the PR workflow

The allowlisted JSON report contains:

- `reportVersion`, `mode`, `outcome` (`plan-only`, `complete`, `partial`, `blocked`), and whether a canonical change exists.
- Baseline/candidate SHA-256 hashes, dataset versions, and global gather times when a candidate exists; no private paths.
- Aggregate requested/readable/unavailable/empty library counts; unique targeted app and HLTB counts; mode-excluded counts.
- Added mappings, per-affected-app IDs, public titles when available, HLTB IDs, four old/new integer-or-null styles, and whether the app was directly targeted or indirectly affected by a shared tuple.
- Successfully checked unchanged entry/app counts, untouched canonical app/length totals, and separate no-lengths/unmapped/failed/unattempted counts.
- Sanitized failure categories, mapping conflicts with both public IDs, request/retry/backoff totals, and budget exhaustion status.

Generate changes from the actual merged relations. Expanding shared tuple changes to all canonical app aliases affects reporting, not request scope. Public names come only from validated game metadata; missing names fall back to app/HLTB IDs. Unknown values remain null in JSON. Successful unchanged, failed, skipped, and untouched are distinct categories with documented denominators.

The report discloses that mappings and public game-specific failure/gap details reveal selected ownership scope. It contains no account attribution, account URLs, raw responses, request queries, secrets, or machine paths. Construct sanitized records instead of serializing raw exceptions or checkpoints.

## Risks / Trade-offs

- [Upstream page shape changes] -> Verify a bounded live sample, retain sanitized fixtures, and fail closed on unrecognized structure; source fixtures alone are insufficient acceptance.
- [Global dataset age cannot express partial per-row age] -> Use local entry history for stale selection and explain the public format limitation in documentation and reports.
- [Loss of local history] -> Treat affected entries as unknown/stale, accepting extra bounded reads instead of pretending they are fresh.
- [Shared HLTB IDs alter untargeted app aliases] -> Fetch only selected IDs, preserve mappings, and enumerate every affected alias in the report.
- [Canonical base moves during a long run] -> Block finalization/resume rather than silently merge against a different baseline or retimestamp observations.
- [Partial upstream coverage] -> Permit a validated partial candidate with explicit failures; blocking mapping conflicts still prevent publication.

## Migration Plan

1. Add the CLI/modules, private-state ignore rule, contract documentation, and offline fixture tests; leave canonical data untouched in the implementation PR.
2. Expand the existing PR job to run merge and refresh tests without live credentials, followed by the existing canonical check.
3. Run a small manually authorized live dry run and refresh against separate output files. Record sanitized scope, budgets, identity/unit checks, candidate/report hashes, and interrupted/resumed evidence.
4. Record `1d` acceptance before implementing `2d`. Proposal/spec validation alone is not CLI acceptance.
5. Rollback removes the CLI and local-state integration; existing dataset, merger, consumers, and release workflow continue to function.
