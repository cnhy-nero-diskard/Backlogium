# Design

## Context

`CI` currently runs six jobs on pull requests and pushes to `master`. The
`master` ruleset requires each of the six job names plus the separate
`Pull request carries a usable release note` check. The migration job runs only
`MigrationTest`. The script job uses unittest discovery and also exercises the
visual-baseline script and the local emulator launcher.

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

Room paths include `BacklogiumDatabase.kt`, `Converters.kt`, `entity/**`,
`di/DatabaseModule.kt`, `app/schemas/**`, `MigrationTest.kt`, and any helper,
fixture, or other input that can change migration behavior or verification.
The v18→v19 migration uses shared logic in `data/diagnostics/Diagnostics.kt`.
Keep a comment beside the path rules explaining that any new migration
dependency outside the existing paths needs a matching rule. Review changes
in flight when landing the router. The deadline archive change, for example,
introduces a separate schema helper and reuses query SQL from a DAO.

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
lightweight PR job. The existing repository contains bulk strict-mode findings.
Strict validation remains appropriate for this individual change.

## Risks / Trade-offs

- A missed dependency could defer a regression until the `master` sweep. Include
  direct schema and migration dependencies, and test the routing matrix with
  representative mixed changes, deletions, and cross-domain moves.
- A job rename can strand PRs waiting on the old required check. Establish the
  stable result before changing the ruleset or renaming the job.
- A router adds a short setup delay before selected jobs start. Keeping it to
  a checkout, a Git diff, and path classification avoids starting either build.

## Migration Plan

1. Land the always-evaluated `CI result` with the six existing jobs still
   unconditional and their names intact. Verify it reports on both PR and
   `master` runs and fails when a required dependency fails.
2. After GitHub can require the new check, update the active `master` ruleset in
   one transition to require `CI result` and the existing release-note check.
   Verify the effective contexts and refresh affected in-flight PRs so they
   report the new check. Keep the existing enforcement and bypass settings.
3. Land PR routing and rename the emulator job to `Room migration tests`.
   Verify representative PR routing and a full push run on `master`. If
   routing needs rollback, restore unconditional jobs under the same
   `CI result` context so the merge rules remain valid.
