# Design

## Context

`CI` currently runs six jobs on pull requests and pushes to `master`. The
`master` ruleset requires each of the six job names plus the separate
`Pull request carries a usable release note` check. The migration job runs only
`MigrationTest`. The script job uses unittest discovery and also exercises the
visual-baseline script and the local emulator launcher. The active `master`
ruleset currently has `strict_required_status_checks_policy: false`, so a PR
does not have to be up to date with `master` before merging.

## Goals / Non-Goals

- Make the PR routing rules auditable in one place and preserve the full suite
  on `master`.
- Keep existing job commands, reports, caches, and the pinned CI emulator options
  in place. Android remains one build boundary for test and release compilation.
- UI test sharding, workflow dependency upgrades, and configuration-cache work
  are outside the routing change.

## Decisions

### Keep one main workflow and classify the complete PR diff

Keep the PR trigger unconditional. Use one lightweight router to compare the PR
base and head through their Git merge base, following the visual-baseline
workflow's existing Git diff approach. Use full history and a NUL-delimited
file list. Disable rename detection so moving a file classifies both the old
and new paths. Pass event SHAs as environment values. A checkout, diff, or
classification error fails routing and is visible in the required result.

Workflow-level path filters can leave required checks pending when a workflow
never starts. A complete local Git diff also avoids relying on a truncated
changed-file list. [GitHub required status check behavior](https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/troubleshooting-required-status-checks#handling-skipped-but-required-checks)

### Route by build inputs and migration dependencies

Matching rows combine. Unit tests include the existing release Kotlin compile.

| Input | Selected main CI jobs on PRs |
| --- | --- |
| General `app/**`, including resources, tests, snapshots and ordinary DAO changes | Android unit tests, lint and Compose screenshot verification |
| `app/src/androidTest/**` | Android unit tests, lint and Compose screenshot verification, plus Room migration tests |
| Room migration inputs | Android unit tests, lint and Compose screenshot verification, plus Room migration tests |
| `gamification/**` | Android unit tests and Compose screenshot verification |
| App build script and shared Gradle files, including root build and settings scripts, properties, `gradle/**`, and wrappers | All four Android jobs |
| `functions/**` or `firebase.json` | Functions build and tests |
| `scripts/**` or a workflow calling the tested release-note or visual-baseline scripts | Script tests |
| `openspec/**` | OpenSpec validation |
| `.github/workflows/ci.yml` or the router and its tests | All six existing jobs and OpenSpec validation |
| HLTB dataset inputs, docs, or other planning-only files without a matching row | Required CI result with no selected jobs |

The app depends on `:gamification` and renders some of its results, so its
changes can affect screenshot verification. Keep HLTB validation and publication
in the existing dataset workflow. Treat Firebase project configuration as a
Functions input because it points at the Functions source and predeploy build.

The `app/src/androidTest/**` row is deliberately broader than migration-test
inputs: `:app:connectedDebugAndroidTest` compiles the instrumentation test APK
before its runner selects only `MigrationTest`. Thus an unrelated instrumentation
source edit retains compile coverage without running every instrumented test.

Room paths include `BacklogiumDatabase.kt`, `Converters.kt`, `entity/**`,
`di/DatabaseModule.kt`, `app/schemas/**`, and `MigrationTest.kt`. These concrete
migration dependencies are also required direct matches: `app/src/main/java/com/example/backlogium/data/local/DeadlineEvidenceSchema.kt`,
`app/src/main/java/com/example/backlogium/data/local/dao/HltbDataDao.kt`, and
`app/src/main/java/com/example/backlogium/data/diagnostics/Diagnostics.kt`.
The database migrations use the deadline schema helper and DAO query, while the
v18→v19 migration uses the diagnostics helper. Add a matching rule whenever a
new migration dependency is introduced; if its dependency path cannot be
confidently enumerated, conservatively route all `app/**` changes to Room
migration tests until the path is registered. Test each named dependency as an
isolated one-file change. Keep a comment beside the path rules with this
registration requirement.

### Make the required check account for selected and skipped jobs

Use a stable `CI result` job that evaluates after all its dependencies even if
one fails or is skipped. On PRs it requires successful routing, complete valid
outputs, and success for every selected job, including OpenSpec when selected.
Accept a skip only for an explicitly unselected job. The job should report why
the workflow ran or skipped each expensive check.

On pushes to `master`, bypass PR classification and run all six existing jobs
regardless of the diff. The result requires all six to succeed. Allowing only
the intentionally unselected PR jobs to skip keeps a router failure from
surfacing as a green merge check.

Use a pinned OpenSpec CLI and `openspec validate --all --no-interactive` for its
lightweight PR job. Keep this repository-wide pass non-strict while legacy
strict-mode findings remain in unrelated active changes; it still checks the
whole repository for validation errors. For every touched, non-archived change
under `openspec/changes/`, also run `openspec validate <change> --strict` so new
or modified active changes cannot add strict-mode findings. This change itself
must pass that strict command. Once legacy findings are addressed, make the
repository-wide pass strict as well.

## Risks / Trade-offs

- A missed dependency could defer a regression until the `master` sweep. Include
  direct schema and migration dependencies, including the named helper and DAO
  paths, and test the routing matrix with isolated paths, representative mixed
  changes, deletions, and cross-domain moves. Instrumentation-only changes must
  still compile the test APK through the migration job.
- With the current non-strict required-status-check policy, advancing `master`
  can make a previously classified PR diff stale: newly introduced migration
  dependencies may not select the migration job. Enable up-to-date required
  status checks before PR routing; verify that an out-of-date PR must update and
  refresh its checks before it can merge. If that freshness gate cannot be
  enabled, do not enable path routing without an explicit decision to accept
  this integration risk.
- A job rename can strand PRs waiting on the old required check. Establish the
  stable result before changing the ruleset or renaming the job.
- A router adds a short setup delay before selected jobs start. Keeping it to
  a checkout, a Git diff, and path classification avoids starting either build.

## Migration Plan

1. Land the always-evaluated `CI result` with the six existing jobs still
   unconditional and their names intact. Verify it reports on both PR and
   `master` runs and fails when a required dependency fails.
2. After GitHub can require the new check, update the active `master` ruleset in
    one transition to require `CI result` and the existing release-note check,
    and enable up-to-date required status checks. Preserve bypass actors and
    other enforcement settings. Verify the effective contexts, confirm an
    out-of-date PR cannot merge until it is updated and CI refreshes, and refresh
    affected in-flight PRs so they report the new check before starting stage 2.
3. Land PR routing and rename the emulator job to `Room migration tests`.
   Verify representative PR routing and a full push run on `master`. If
   routing needs rollback, restore unconditional jobs under the same
   `CI result` context so the merge rules remain valid.
