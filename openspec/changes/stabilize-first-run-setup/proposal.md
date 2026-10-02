## Why

Issue [#158](https://github.com/cnhy-nero-diskard/Backlogium/issues/158), prioritized by [#174](https://github.com/cnhy-nero-diskard/Backlogium/issues/174), reports setup failures and unavailable recovery during concurrent Steam work. Source inspection confirms that queued retries become stored failures, onboarding hides Retry, completed-run selections mask edits, and a history import can be consumed before a library baseline exists; the reported cascade itself still needs reproduction.

## What Changes

- Separate setup's foreground attempt from the lifetime of its underlying work. Show queued/waiting, running, retry scheduled, completed, failed, cancelled, and skipped truthfully, without waiting indefinitely or treating concurrent work as failure.
- Persist and reconcile each admitted stage's exact work identity after leaving setup or restarting the process. Keep failure isolation and the existing worker-owned detached notifications, including the completion-times repair from #111.
- Offer per-stage recovery in onboarding and Settings once the foreground attempt settles. Retry only the requested stage, preserve successful siblings, and make edited selections visibly match the next run.
- Preserve the underlying jobs' existing concurrency policies. Attaching to a queued retry does not promise immediate execution; neither periodic sync nor the live monitor is cancelled or replaced merely to recover setup.
- Report domain-level library-sync results instead of assuming WorkManager success means a committed library. Expose durable, same-account evidence that a valid baseline exists, including a confirmed empty library.
- Add an explicit optional history-import decision before first arrival at Home, including after declining setup. Reuse the existing lifetime-playtime-to-XP operation, offer Skip/do later and the existing Settings control, and explain that Steam totals cannot reconstruct dated sessions, quests, or streaks.
- Guard the shared import operation against an unavailable baseline and make its raw commit plus derived recomputation recoverable and idempotent across concurrent sync, repeated invocation, and process death.
- Deliver recovery before the history-choice milestone. No progress percentages are invented, no setup stage becomes mandatory, and no implementation is included in this proposal.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `first-run-setup`: Truthful work states, foreground settlement, durable per-stage observation, non-destructive retry and editable next-run selection.
- `onboarding-credentials`: A durable optional history decision between setup and Home, with explicit exit and interruption semantics.
- `steam-sync`: Attributable library-poll outcomes and authoritative same-account baseline readiness.
- `playtime-backfill`: Baseline eligibility, truthful lifetime-counter limits, atomic one-time import and recoverable recomputation.
- `app-settings`: Setup recovery presentation and the same import readiness/recovery policy in Data & privacy.

## Impact

Planning targets `work/setup`, `data/setup`, `ui/setup`, `ui/onboarding`, the Home takeover/startup boundary, Steam sync scheduling/results, the profile repository, and the existing history-import use case and Settings controls. A minimal persistence migration may be needed for baseline evidence, per-stage work ownership, and pending import recomputation; legacy credentials, outcomes, and completed imports must remain compatible. No new remote API, library dependency, cloud requirement, or polling cadence is proposed.

Missing-achievement diagnosis and active-game achievement watch (#159/#175), navigation/insets (#157), broad progress/offline feedback (#161), cloud historical re-filing, and synthetic pre-install session history are outside this change.
