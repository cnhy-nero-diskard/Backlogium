# Design

## Context

See `proposal.md` for motivation and the dependent PR #191 change for existing discovery authorities. This design is warranted by cross-module failure attribution, private-artifact compatibility, and access-control/privacy constraints.

The fix branch is based on PR #191 head `a4a926e1` and tracks `origin/feat/hltb-new-game-discovery`. The occupied discovery worktree is inspection-only. Mainline is `master`, but is not the immediate fix target.

`SearchSession.search()` currently hides session setup inside a per-title operation. `RequestBudget` stops on HLTB 401/403, before reading the denied body. `runDiscovery()` catches that setup failure as a first-app failure; `reviewSummary()` then loses its phase. Other setup failures can repeat initialization for every app. Version-1 private reviews enforce exact fields, and refresh binds the entire input bytes for approval/resume integrity.

Evidence boundaries:

- Historical failures established HTTP 403, an error key, and no token. The error value was not captured; the branch name is not evidence.
- One user-authorized GET with Android's existing headers returned HTTP 200, JSON, 288 bytes, a nonempty token, no error key, and no detected challenge markers. No POST, retry, or redirect follow-up occurred; no token was printed or persisted.
- This supports combined-header alignment, not causal attribution to one header or assurance that searches work. No further live requests are authorized.

## Goals / Non-Goals

**Goals:** Make session-level failures visible and deterministic; keep app attempts truthful; retain the existing network budget and fail-closed rules; preserve existing review consumption without rewriting approvals.

**Non-Goals:** A new scraper/transport, header cycling, network/IP workarounds, denial retries, payload changes inferred from third-party clients, full-library live acceptance, Android edits, dataset application, automatic PR merges, or importing private artifacts into Git.

## Decisions

### 1. Centralize discovery headers at the session boundary

Use a single immutable discovery header profile matching Android's existing user agent, `Origin`, homepage `Referer`, and `Accept: */*`. Apply it explicitly to initialization and same-origin homepage/chunk reads. Search POSTs reuse that identity/context and keep explicit JSON content/accept semantics and session headers/body fields. Token-only initialization remains valid.

Keep `RequestBudget` defaults for Steam and direct-page refresh callers unchanged. Do not copy Android's rejection recovery. Alternatives rejected: change the shared default UA globally (unnecessary refresh risk); change only initialization (possible session identity mismatch); try several profiles after denial (violates stop policy).

### 2. Carry fixed setup context without raw error information

Record setup failures at the boundary where their phase is known: `session-init` or `endpoint-discovery`. Keep category from the existing fixed allowlist and an HTTP status only when observed and validated. If the transport needs to preserve status, add narrowly scoped metadata to internal categorized errors; public serializers construct allowlisted diagnostic objects rather than serializing exceptions.

Continue rejecting HLTB denial without interpreting its response body. Cancel/abort an unread rejected response safely so failing early does not leave a response stream running. Never infer `failOnRequest`, firewall identity, or challenge absence from unread data.

Alternatives rejected: arbitrary error text or URL diagnostics (privacy risk); reporting only `access-denied` (loses phase); treating an init 403 as endpoint rotation (breaks existing contract).

### 3. Separate readiness from app attempt accounting

Make session readiness an explicit operation before an otherwise searchable app's POST. Initialize lazily, so empty/unsearchable scope issues no HLTB request. On expired sessions, use the same readiness boundary before the next POST. After setup fails, stop setup/search for the rest of the invocation even for malformed responses or exhausted transient retries, not just globally stopping categories.

Per-app outcome accounting is based on whether that app's search POST actually began, never whether total requests increased during initialization. A denied POST is the app's failure; a denied initial/renewal GET is a run-level setup failure and leaves that app unattempted. Existing successful empty results remain unmatched. If setup fails during a broader sequence, preserve any candidates and the existing partial-search category while adding the run-level setup diagnostic; do not erase an already attempted app's results.

A setup failure before any game POST yields blocked/exit 1. A setup failure after any game POST began yields partial/exit 2. Preserve existing outcomes for no setup failure, invalid titles, zero scope, search failures, and budget stops outside setup.

Alternative rejected: use `attemptsBefore` to infer an app attempt (counts init/chunk work); blanket preflight before scope filtering (unneeded requests); repeat failed setup once per remaining app (amplifies systemic failures).

### 4. Extend private review v1 narrowly and retain exact validation

Add optional top-level `sessionFailure` containing exactly `{ phase, category, status }`; status is null when no HTTP status is known. Omit the field when no setup failure exists. Accept both the original exact v1 shape and that shape plus this validated field; reject any other extra fields or invalid diagnostics. Keep per-app shapes and existing categories unchanged. Original reviews remain readable without migration; old binaries need not accept newly extended artifacts.

Include this diagnostic in execution reports and `--inspect` summaries. Store it in the review, not only the initial report, so credential-free inspection retains the explanation. Do not derive or rewrite it from arbitrary app categories. Keep the full-file approval hash, baseline identity, review path guards, and refresh input race checks unchanged.

Alternatives rejected: report-only metadata (lost during offline inspection); a new review version (unnecessary migration for one optional allowlisted field); relaxing exact-field validation generally (unsafe).

### 5. Validate with fixtures and stage live acceptance separately

Fixture tests assert exact outgoing initialization/discovery/POST header values and unchanged Steam/direct-page defaults. Cover initial and renewed setup failure, both 401/403 and 200 challenge pages, malformed initialization, transient exhaustion, endpoint-discovery phases, and budget exhaustion before POST. Assert no later requests, correct outcome/exit, valid saved reviews, offline inspection, prior-result preservation, approval compatibility, and absence of secrets/raw error text.

Use Node 22 for dataset and skill suites, canonical byte checks, strict OpenSpec validation, and diff checks. The earlier one-GET result is supporting initialization evidence only, not feature acceptance. Any live search smoke test requires a new explicit bounded grant; do not reuse private accounts or credentials from another session.

## Risks / Trade-offs

- [Upstream or network conditions change] → Retain denial stops and explicit diagnostics; do not promise that successful initialization guarantees POST availability.
- [Optional metadata is incompatible with old readers] → Document new-writer/old-reader limitation, preserve old-review/new-reader compatibility, and keep strict validation.
- [Setup budget exhaustion differs from a game failure] → Test request-start accounting at the transport boundary and around session expiry, not merely final category strings.
- [Dependent capability is not in main specs yet] → Keep this delta additive and sync after the introducing PR #191 delta; never synthesize a duplicate main capability during implementation.
- [Feature branch advances] → Recheck upstream before publication and stop on rejected push rather than overwrite or mutate the occupied worktree.

## Migration Plan

1. Implement the fix only in the isolated fix worktree, without changing predecessor artifacts or main specs.
2. Update documentation for new optional diagnostics and blocked setup semantics; validate legacy review/refresh fixtures.
3. Commit/push only scoped fix files and artifacts when implementation is separately authorized. A fix PR targets `feat/hltb-new-game-discovery`; its reviewed integration enables testing #191 without merging #191 first.
4. If #191 lands before integration, verify the corresponding discovery revision on `origin/master` before retargeting. Do not rewrite another worktree or force-push.
5. Rollback can remove the fix commits; preserve private reviews, noting that old readers will not accept the optional diagnostic field on new artifacts. No dataset or Android migration is required.
