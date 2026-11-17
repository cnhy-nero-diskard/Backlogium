# Design

## Context

See `proposal.md` for motivation and the two delta specs for contracts. Planning is grounded in merged baseline `65e01b3d`, not the initially stale local `master` reference. Only this session's branch was fast-forwarded; occupied worktrees were not modified.

Relevant authorities:

- `refresh-targets.mjs` already resolves the readable owned-library union, applies app restrictions, overlays reviewed correspondences, and rejects correspondence conflicts globally.
- `refresh-run.mjs` freezes inputs, fetches distinct HLTB game pages, stores real observation times, and builds a full candidate through `merge.mjs`. It already permits reviewed correspondence-only additions when fetching yields no usable lengths.
- `RequestBudget` currently supports GET only. It enforces serial starts at least one second apart, a 15-second timeout, a 5 MiB body cap, two transient retries, finite attempt/runtime budgets, and immediate HLTB access-denial stops.
- Android's `ScrapingHltbDataSource` and `HltbBundleParser` define search session initialization, endpoint discovery, request payloads, and candidate extraction. `HltbMatcher.scored` provides ranking without classification; the classifier must not be used here. `HltbQueryGenerator` and broader scoring define explicit zero-result recovery.
- `hltb-dataset` and archived `decouple-hltb-fetching` separate reviewed correspondence from completion content and prohibit replacing manual answers with guesses. Archived `expand-hltb-match-discovery` explains why broader search is bounded and not automatic. Archived `1d-add-targeted-hltb-refresh` defines private evidence, full-baseline merge, and output-pair integrity. Change `2d-add-hltb-refresh-pr-skill` supplies assistance and offline verification; it remains unarchived on this baseline.
- `verify-pair.mjs` accepts tool/report version 1, checks preservation and complete actual-diff coverage, and does not attest to human approval, live fetching, private scope, or report counters.

## Goals / Non-Goals

**Goals:** Keep discovery, exact-pair approval, completion observation, candidate verification, application, and publication as distinct authorities. Reuse existing scope, network safeguards, merge rules, and fixture evidence rather than introduce a parallel refresh implementation.

**Non-Goals:** Android UI or storage changes; a web review application; general web search; full HLTB enumeration; scheduled work; automatically approving even exact matches; changing public schema-v1 timestamps; changing existing resolved Android match behavior; live requests during automated acceptance; candidate application or Git publication during this change.

## Decisions

### 1. Add a separate discovery command, not an implicit search mode

Planned entry point: `node tools/hltb-dataset/discover.mjs`. Reuse Steam target options, optional repeated app-ID restrictions, private environment credentials, request/runtime budgets, and `--dry-run`. Add `--broader` as explicit authorization for zero-result title variants, `--output` for a new review file, and `--report` for sanitized discovery diagnostics. Default review/report locations are in a fresh opaque run directory under `tools/hltb-dataset/.local/discovery/`; do not reuse the fetch defaults.

Existing `refresh.mjs` keeps its meaning: resolve scope and fetch only known/reviewed IDs, without searching. Discovery filters out canonical mapped apps and obtains only first-page search suggestions for uncovered apps. Its dry run contacts Steam only and does not initialize HLTB, create review files/checkpoints, or mutate observation history. An explicit plan report remains allowed.

Alternative rejected: automatically searching gaps during missing-only refresh. It mixes consent, budgets, search previews, and authoritative observations and makes a familiar command unexpectedly issue new searches.

### 2. Port search behavior with fixture parity, not Kotlin execution

Keep Node 22 tooling dependency-free and outside Gradle. Isolate pure search-response parsing, normalization/scoring, broader-query generation, and session extraction so fixtures can compare the existing Android examples with the Node behavior. Reference the Android source and test cases in test comments; retain normalized edit-distance primary ranking, conservative broader scoring, edition/subtitle handling, safe Roman/Arabic sequel variants, and candidate-ID deduplication. Give equal-score candidates a documented deterministic order.

Primary search uses the original Steam title, page 1, at most 20 results. With explicit `--broader`, only a successful empty primary search permits at most three distinct additional queries. Deduplicate repeated candidates, keep the richest validated preview, and score against the original title. Missing or unsafe Steam titles are reported as unsearchable failures, never replaced with a guessed query. A successful empty list is unmatched, not a parse failure. Malformed responses are failures, not empty lists.

Do not port `classify`, confidence thresholds, or automatic resolution. Every discovered pair starts pending. No cover or direct game-page requests are needed to present search previews; links can be constructed locally from numeric IDs.

Alternative rejected: launch Android or introduce a JVM bridge to invoke the search implementation. That couples the standalone maintainer tool to an installed app/Gradle and is not practical code reuse. Shared behavioral fixtures are the narrower maintenance cost.

### 3. Extend the existing budgeted transport without copying access-control recovery

Extend the request boundary to support controlled POST bodies and required search headers while retaining GET callers and fixture injection. Do not accept arbitrary request URLs/headers from review files. Every Steam read, initialization, homepage/chunk read, candidate endpoint validation, POST, and retry consumes the same approved invocation budget. Retain the current concurrency, spacing, timeout/body caps, retries, Retry-After handling, and default 500-attempt/900-active-second limits.

Use the app's fast-path initialization and bounded same-origin public-bundle endpoint discovery. Permit discovery only for endpoint absence such as a 404, not for denial, a challenge, throttling, transient failures, or malformed successful initialization. Inspect at most 12 distinct referenced same-origin chunk paths and validate at most four distinct endpoint candidates. Restrict extracted paths to recognized chunk/API routes and reject scheme/host changes, credentials, ports, traversal, query injection, and redirects. Treat bundles as text; never evaluate upstream JavaScript.

Search tokens/session state stay in memory only. An HLTB 401/403 or challenge at any stage stops all subsequent HLTB work. Unlike Android's current rejection re-resolution, the maintainer CLI does not reset a session after access denial. Challenges in successful HTML responses must be recognized before interpreting content as a normal parse error. Transient errors use bounded retry without endpoint rediscovery. Stop status propagates to all remaining apps as unattempted rather than unmatched.

Alternative rejected: copying the app transport wholesale, including its rejected-search retry. It would weaken the existing CLI stop rule. No proxy rotation, CAPTCHA solving, cookie import, browser workaround, or access-control bypass is added.

### 4. Use a private versioned review artifact with separate state axes

Use review schema version 1, distinct from public dataset schema version 1. Store:

- Opaque discovery ID, baseline SHA-256, creation time as discovery metadata, and a fingerprint of the frozen app scope/search options.
- The scoped uncovered apps, each with a positive `appId`, sanitized Steam title, and search outcome (`candidates`, `unmatched`, `failed`, or `unattempted`), plus a sanitized failure category when needed.
- Per-app candidates with positive `hltbId`, sanitized HLTB title, `previewMinutes` in the four established styles, source/query provenance, score, and a decision of `pending`, `approved`, or `rejected`.
- Aggregate library/request summaries and the explicit warning that previews and discovery times are not direct-page observations or successful-fetch times.

Do not store Steam account IDs, user-to-game attribution, keys, search tokens, cookies, raw exceptions, or upstream responses in the review artifact. Exclude unsanitizable text rather than publishing it; always preserve numeric identity. Queries and titles remain untrusted data. The artifact is private because it describes owned apps and human review, even without account IDs.

Keep search outcome and candidate decision independent: a successful search can have pending, approved, or rejected candidates. Fetch observation is a third axis, stored by the existing fetch checkpoint. Reject duplicate apps/candidate IDs, invalid decision values, absent statuses, invalid minute previews, or more than one approval per app. A person reviews the local JSON and changes only decisions; an agent can record exact-pair approvals explicitly given in chat. Neither confidence nor a general instruction to fetch permits changing decisions to approved. Manual IDs not found by search can still use the established reviewed-mapping contribution path; no new manual-link path is required.

Require all review files inside ignored `tools/hltb-dataset/.local/` (custom destinations may be beneath it). Guard real paths and existing inode aliases against source, inputs, reports, history, and checkpoint files. Use atomic private writes and refuse a pre-existing review destination. Preserve completed per-app discovery results on a controlled stop; do not erase decisions on rerun. This first version does not add discovery resume: use a fresh scoped run when additional search is explicitly approved. Fetch resume remains supported.

The current fetch path guard rejects every input beneath `stateDir`, whose default is `.local/`. A new review input in the required private location would therefore be rejected by a naive adapter. Add a narrowly scoped allowance for read-only review artifacts beneath `.local/`, while still forbidding aliases/overlap with source, outputs, `runs/`, and `history.json`; retain legacy input guard behavior. Document a sibling layout for custom state directories. Do not remove blanket protections without replacement or force maintainers to move reviews into tracked paths.

Add credential-free `discover.mjs --inspect <review-file>` for offline validation and current decision summaries, mutually exclusive with search execution options. Derive its counts from the current file rather than trusting the original discovery report, which predates human decisions. It performs no Steam/HLTB requests and changes neither decisions nor datasets; only an explicitly requested sanitized report can be written to a separate guarded destination.

Alternative rejected: put suggestions directly in a schema-v1 contribution. That format has no review status, so accidental consumption would promote guesses into reviewed mappings. HTML/browser review and cryptographic approval attestation are unnecessary for the initial local workflow; schema validation is not proof of human consent.

### 5. Add a review input adapter to the existing fetch command

Add `refresh.mjs --review-candidates <private-review-file>`, mutually exclusive with `--reviewed-mappings`. Keep all existing legacy flags and schema validation working. Parse review input before network activity, validate baseline identity and decisions, then adapt approved pairs into the same internal reviewed-correspondence shape with no supplied length tuples. Retain the original input kind/hash and review dispositions in the plan rather than laundering the artifact through an unbound temporary contribution.

Reject stale baseline hashes and global correspondence disagreements before writing any candidate. Only apps approved within both artifact scope and the freshly resolved owned-library/app intersection can be added. Out-of-scope approvals are reported as excluded, not fetched; conflicts remain blocking even outside current scope, matching the existing contribution rule. Pending and rejected pairs never authorize mapping or requests, although the same numeric HLTB ID can legitimately be fetched for a different approved or canonical mapping.

Change selection to union normal mode-selected IDs with IDs of every newly admitted reviewed mapping, then deduplicate. This also gives legacy reviewed additions a direct read when an existing alias already supplies lengths. Without this exception missing-only would add an alias using old shared lengths without the fresh fetch requested by this workflow. The review file's entire content hash participates in checkpoint compatibility; any changed decision, including approval withdrawal, blocks a resume before requests. Recheck input bytes before finalization as well as the baseline so an edited approval cannot race an active run into candidate output.

Reuse direct-page parsing, identity/unit/range validation, entry history, and the single canonical merge. A mapping approval and a successful length fetch remain distinct: preserve existing correspondence-only admission on failed/no-lengths/unattempted reads, but show those exact outcomes. Retain existing shared lengths if present; for a new HLTB ID omit an unknown tuple. Never use preview values, discovery time, or retained baseline tuples as fresh observations. The resulting candidate is the whole baseline plus additions/validated updates, not a contribution-sized replacement. Global `gatheredAt` limitations and partial-style merge semantics remain unchanged.

Alternative rejected: a second candidate builder or automatic application. The existing refresh owns canonical merging and source protection; application remains a later explicitly authorized action.

### 6. Extend reporting additively and preserve verifier compatibility

Give discovery its own versioned report, visibly distinct from a refresh execution report. It never advertises a dataset candidate hash. Keep public refresh tool/report version 1 and all existing required fields; add a bounded allowlisted `review` section only when review input is used. It carries artifact version/hash and separate app/pair counts and identifiers for candidates, approved, rejected, pending, and excluded decisions. Do not expose private paths, queries, account IDs, or raw review objects. The existing integrity checker accepts additive fields; retain its supported versions and test this explicitly.

`unmapped` continues to enumerate scoped apps with no admitted mapping, irrespective of whether discovery found no results, awaits review, or was rejected. Approved correspondence count is not fetched-entry count. Fetch failures, no-lengths, and unattempted remain distinct unique-HLTB categories; affected apps and added mappings are derived from the actual full candidate, including every indirect canonical alias of a changed shared tuple. A rejected candidate that happens to share an HLTB ID is not an admitted alias.

Pair verification establishes byte/schema/diff consistency, not human approval or live provenance. It must still pass on the new candidate/report pair. Assistance labels review/coverage counts as reported and does not claim a fresh fetch from a preview or a self-consistent historical report. Refused suggestions are accounted decisions; they are not transport failures. Any scoped app remaining unmapped or unfetched retains the current partial-coverage meaning.

Alternative rejected: changing public dataset or report versions merely to carry private review data. The dataset stays unchanged in shape; the new private schema and additive report section suffice.

### 7. Keep documentation and acceptance staged

Update the README, private-artifact format documentation, skill body, and command reference together during implementation. Show discovery dry run, authorized bounded search, private exact-pair review, fetch dry run using the approved artifact, authorized direct fetch, offline pair verification, and an explicit stop before apply. Keep the Android Match Center plus schema-v1 contribution export as an alternative reviewed-input source, noting that the export format does not itself attest to per-pair human review. Disclose owned-app publication and global-age limitations at the same points as today.

Add skill examples where an exact result remains pending, a user approves only one of several pairs, a rejection shares an approved ID, an approval changes before resume, and inspection requests stay offline. Automated tests use injected network, clock, sleep, and fixture bodies, with unexpected requests failing the test. No Steam key or live HLTB request is needed. Existing workflow glob commands already include new dataset test files; do not change release behavior.

## Risks / Trade-offs

- [Node and Kotlin implementations drift] → Port the observable behavior with shared/referenced fixture cases and clearly scoped parity; do not change Android to make the CLI easier.
- [HLTB search protocol changes after fixtures were captured] → Fail closed, preserve partial discovery, and state fixture-only acceptance honestly. A future bounded live smoke test requires separate scoped approval; none is part of this planning session.
- [A wrong correspondence looks exact] → Default every pair pending, display both identities and titles, and require explicit approval before admission.
- [Review files or text imply permissions] → Validate data, ignore embedded instructions, and keep agent consent separate from artifact consistency.
- [Discovery and fetching each have budgets] → Report them per invocation and obtain separate stage grants; neither inherits an unlimited budget from the other.
- [Approvals race changing files or baselines] → Bind bytes to the plan, verify again before finalization, and block changed inputs instead of silently reconciling.
- [New mapping has failed/no-length fetch] → Preserve existing mapping-only semantics and baseline values; report partial evidence rather than populating previews or claiming freshness.
- [Mapped shared entry affects untargeted aliases] → Fetch the selected ID once, report actual affected aliases, and never infer approval for unrelated suggested mappings.

## Migration Plan

1. Add versioned private review validation, path protection, and pure fixture-tested search/ranking modules without modifying dataset data.
2. Extend the existing budgeted transport and add the explicit discovery command with bounded partial reporting and no final-value merge.
3. Add the fetch input adapter, review binding, shared-new-mapping selection exception, and additive accounting; preserve legacy tests and frozen-run compatibility. Keep legacy checkpoint version 1 valid when no review artifact is used; bind the new input kind explicitly so old checkpoints cannot masquerade as reviewed-artifact runs.
4. Document and fixture-test the entire staged workflow, including skill pair verification. Leave `dataset.json`, main specs, and Android code unchanged during implementation.
5. Run required Node 22 suites, canonical dataset check, diff checks, and strict change validation. Record exact results and the distinction between tested baseline functionality and unimplemented/new acceptance checks.

Rollback removes the optional discovery command and review adapter; canonical dataset format, legacy contributions, existing private fetch history, and Android consumers remain valid. Do not delete private review artifacts on rollback. No automatic apply, commit, push, PR, merge, or release is part of this change.
