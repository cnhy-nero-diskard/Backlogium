# Tasks

## 1. Introduce the stable CI result

- [x] 1.1 Add the always-evaluated `CI result` to the current unconditional workflow with all existing job names intact. Verify success when all six jobs pass and rejection when a required dependency fails, is cancelled, or is unexpectedly skipped.
- [ ] 1.2 Land the result job and confirm successful PR and `master` check runs. Update the `master` ruleset to require `CI result` and the existing release-note check, and enable up-to-date required status checks while preserving bypass actors and other enforcement settings. Verify the effective contexts, confirm an out-of-date PR cannot merge until it is updated and CI refreshes, and refresh an affected PR before starting stage 2.

## 2. Apply PR routing

- [ ] 2.1 Compute the complete base-to-head PR diff from the merge base and classify it with the rules in the design. Add one-file routing regressions for a docs-only PR, each build boundary, shared Gradle inputs, each named migration dependency (`DeadlineEvidenceSchema.kt`, `HltbDataDao.kt`, and `data/diagnostics/Diagnostics.kt`) in isolation, an unrelated `app/src/androidTest/**` source, a production-only API change such as `CollectionMembershipRepository.kt` with no `androidTest` edit, scripts, deletions, cross-boundary moves, and workflow or router edits. Include a mixed-path case such as an instrumentation-only edit plus `functions/**` and assert the union of selected jobs. Require registration of each newly introduced migration dependency, with the design's `app/**` fallback when its path cannot be confidently enumerated. Put the routing rationale beside the path rules.
- [ ] 2.2 Apply the router outputs to the existing jobs and rename the emulator job to `Room migration tests`. Validate the workflow and review the job diff for preserved commands, test selectors, reports, caches, and emulator options. Verify a production-only API change selects Android unit tests and that this job's device-free `:app:assembleDebugAndroidTest` builds the full instrumentation APK; verify an unrelated instrumentation-source edit still selects the migration job whose runner executes only `MigrationTest`. Do not run every instrumented test. Verify pushes to `master` bypass classification and select every existing job.
- [ ] 2.3 Add the PR-only OpenSpec job with a pinned CLI. Keep repository-wide `openspec validate --all --no-interactive` non-strict while unrelated legacy strict findings remain, and strictly validate every touched, non-archived change under `openspec/changes/`. Verify the current change passes strict validation, a malformed change is rejected, and legacy strict findings do not block the bulk non-strict pass.
- [ ] 2.4 Have `CI result` validate routing and job outcomes. Verify that failed routing, missing or invalid outputs, and any selected job that fails, is cancelled, or is skipped yield a failed result. Verify that a docs-only PR with valid routing and expected skips passes.

## 3. Verify the rollout on GitHub

- [ ] 3.1 Check representative hosted PR runs for docs or OpenSpec, Android, Functions, and migration changes. Verify that each selects the expected jobs, that the required release-note check still reports, and that the independent visual-baseline and HLTB workflows still trigger on their existing events.
- [ ] 3.2 After the routing change lands, inspect a `master` push run for all six jobs and verify the required contexts still resolve on a PR. Validate this OpenSpec change with `openspec validate issue134-ci-scope-pr-checks-patch --strict`.
