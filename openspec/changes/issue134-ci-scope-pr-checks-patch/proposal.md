# Proposal

## Why

Backlogium runs every main CI job on every pull request, adding minutes of unrelated
Gradle, emulator, or npm work to changes that touch only one build system or only
documentation. [Issue #134](https://github.com/cnhy-nero-diskard/Backlogium/issues/134)
proposes routing PR checks by build inputs and migration risk while preserving the
full sweep on `master`.

## What Changes

- Route the six existing CI jobs by the files a pull request changes. Keep the Android
  test and release compile, lint, screenshot verification, Room migration, script
  test, and Functions commands and coverage.
- Compile the instrumentation APK in the device-free Android unit tests job so
  production-only app, gamification, and shared Gradle changes also check the
  instrumentation sources that depend on their APIs.
- Run Room migration tests before merge when a PR can affect upgrade behavior.
  Rename the existing emulator job to reflect the suite it runs.
- Require a stable CI result that accounts for both routing and the selected jobs.
  Introduce it before switching required check names in the `master` ruleset.
- Validate OpenSpec changes in a lightweight PR job. Keep the full existing suite
  unconditional on pushes to `master`.

## Capabilities

### New Capabilities

### Modified Capabilities

- `schema-migration` scopes pre-merge verification to migration-relevant changes
  and retains automatic verification on pushes to `master`.

## Impact

The implementation affects `.github/workflows/ci.yml`, its routing logic and tests,
and the GitHub repository ruleset for `master`. The existing independent release-note,
visual-baseline, and HLTB workflows continue to report their own results. Android
runtime behavior, cloud deployment, and test scope stay outside this change.
