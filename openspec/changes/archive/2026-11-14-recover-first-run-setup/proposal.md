# Proposal

## Why

First-run setup can call a queued retry a failure, hides onboarding retry controls, and does not offer the existing Steam history import before Home. Issue #158 affects the reliability of getting started, especially when setup overlaps manual/periodic sync and live monitoring.

## What Changes

- Reproduce the concurrent-work path with recorded stage/work identities, worker states, attempt counts, errors, and reuse/replacement decisions before changing scheduling behavior.
- Distinguish waiting, running, retry backoff, true failure, skipped, and completed stages; queued background retries remain observable rather than being recorded as failures.
- Allow onboarding retries and edited selections after the setup attempt settles, preserving successful outcomes and avoiding duplicate active work.
- Keep a clear continue/exit path during long waits and recover exact outstanding work after process death, including detached completion-times work from #111.
- Offer an explicit, default-off Steam history import choice before first arrival at Home, with Skip and the existing later Settings entry. Reuse the current import operation and explain that lifetime totals do not reconstruct timestamped sessions.
- Verify repeated import and overlapping sync cannot duplicate credited progress.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `first-run-setup`: Truthful ongoing stage states, non-blocking durable recovery, onboarding retry, and edited-selection restarts that preserve successes.
- `onboarding-credentials`: An optional history-import decision before first arrival at Home, without changing credential verification or later credential editing.
- `playtime-backfill`: Shared onboarding/Settings import readiness and disclosure, with safe repeat/interruption/concurrency behavior.

## Impact

Android setup runners, coordinator/state persistence, shared setup UI, onboarding flow, and the existing profile/history-import repository boundary. Tests cover WorkManager observation, coordinator recovery, onboarding controls, and import integrity; affected screenshot fixtures/goldens ship with UI changes. Existing Steam sync and presence contracts remain unchanged, including distinct periodic/manual scheduling, best-effort monitoring, transaction-time baseline reads, and the on-device engine as the only derived-state author. No cloud deployment or new dependency is planned.
