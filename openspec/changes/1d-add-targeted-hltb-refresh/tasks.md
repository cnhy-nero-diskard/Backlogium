## 1. CLI contract and private inputs

- [x] 1.1 Add the Node 22 refresh entry point, argument/help contract, mode/threshold validation, and environment-only Steam key handling; verify invalid IDs/options fail before network work and help documents the planned exit codes.
- [x] 1.2 Add ignored private output/state defaults and reject output paths that alias source/mapping/state inputs; verify ignore coverage, path guards, and secret/account redaction fixtures without modifying canonical data.

## 2. Library targeting and reviewed mappings

- [x] 2.1 Implement current owned-library resolution with validated empty/unavailable distinctions; verify readable, empty, malformed, private/unavailable, and mixed-user fixture outcomes and aggregate account-free reports.
- [x] 2.2 Implement app-ID union/intersection and unique HLTB selection for all three modes; verify overlapping libraries, out-of-scope app restrictions, shared HLTB IDs, and missing-only/stale/refresh-all selection boundaries.
- [x] 2.3 Admit selected reviewed mappings from schema-v1 input, ignore its length values as fetch evidence, and reject correspondence conflicts; verify unmapped titles stay reviewable and conflicting/out-of-scope mappings never enter a candidate.

## 3. Direct observations and request bounds

- [x] 3.1 Implement direct-page structured parsing using sanitized existing page examples as references; verify requested-ID mismatches, challenge/error payloads, malformed fields, nearest-minute conversion, unknown/zero values, and numeric bounds.
- [x] 3.2 Implement finite timeout/concurrency/retry/backoff/request/runtime budgets; verify 429 Retry-After, transient 5xx/transport recovery, access-denied termination, and budget exhaustion with injected clock/network fixtures.
- [x] 3.3 Deduplicate shared-entry requests and classify usable, unchanged, no-lengths, failed, and unattempted outcomes; verify one successful fetch per unique ID and retention of existing tuples after all-null or failed reads.

## 4. Freshness evidence and resume

- [x] 4.1 Implement private per-entry successful-fetch history bound to identity/value fingerprints; verify unchanged successful observations advance local evidence, failures/no-lengths do not, and missing/incompatible history is eligible as unknown/stale.
- [x] 4.2 Persist frozen plan and atomic per-entry checkpoints with actual observation times; verify an interrupted run resumes only remaining work without duplicating successful requests or retimestamping saved observations.
- [x] 4.3 Reject incompatible baseline, options, reviewed mapping hash, and state version on resume; verify each rejection leaves source/output data unchanged and does not broaden the saved scope.
- [x] 4.4 Implement the dry-run path that resolves Steam scope but performs no HLTB fetch or candidate/checkpoint/history mutation; verify request counts and file/history comparisons, including a partial-library plan.

## 5. Canonical output and public report

- [x] 5.1 Build schema-v1 contributions from genuine timed observations and reviewed correspondence-only additions, then merge once using the existing exports; verify null handling, failure retention, older-observation precedence, one version increment, and byte-identical redundant output.
- [x] 5.2 Finalize a separate atomic candidate/report pair bound to baseline/candidate hashes after rereading source; verify source-change rejection and recovery from interrupted pair writes, keeping canonical input untouched.
- [x] 5.3 Implement allowlisted reportVersion 1 and documented complete/partial/blocked/plan outcomes; verify actual-diff coverage, all four styles, indirect shared-entry aliases, denominator-specific unchanged totals, mapping gaps/conflicts, no-lengths, and retry/budget summaries.
- [x] 5.4 Verify public output privacy with adversarial credentialed errors, raw-response fields, account names/URLs, and private paths; confirm only public game details and aggregate library outcomes can reach candidate/report/stdout/stderr.

## 6. Documentation, CI, and tool acceptance gate

- [x] 6.1 Document command examples, private/unreadable scope behavior, reviewed mapping intake, ownership disclosure for mappings/report details, resume limits, and the global gatheredAt limitation in README/FORMAT; verify examples match CLI help and distinguish local entry freshness from schema-v1 consumer age.
- [x] 6.2 Expand dataset PR CI to include offline refresh tests alongside existing merge tests and canonical validation; verify the job requires no credentials/network and uses the same documented local checks.
- [x] 6.3 Run the full dataset-tooling test set and canonical check, plus `openspec validate 1d-add-targeted-hltb-refresh --strict`; verify passing results and a clean whitespace check, recording exact commands without claiming live acceptance from fixtures.
- [x] 6.4 With explicitly supplied/authorized targeting inputs, verify a bounded live direct-page sample, library-scoped dry run, and separate-output refresh; record sanitized identity/unit checks, scope/request counts, candidate/report hashes, and source-byte preservation. Leave this task open if inputs or access are unavailable.
- [x] 6.5 Exercise partial failure and interrupted/resumed execution across the CLI/report contract, combining a bounded live successful sample with deterministic failure injection where needed; verify retained failed values/evidence, unchanged fetch timestamps on resume, and complete report accounting.
- [x] 6.6 Record an acceptance handoff for `2d-add-hltb-refresh-pr-skill` with tool/report version, implementation revision, passing offline results, bounded live evidence, and known global timestamp limitations; verify every issue #162 tool criterion is traced and no skill implementation or data publication occurred in this change.
