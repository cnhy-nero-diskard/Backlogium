# Tasks

## 1. Concurrent-work investigation

- [x] 1.1 Extend deterministic WorkManager/setup tests for reuse of existing manual work, overlap with a running periodic poll and live monitoring, constrained waiting, and retry backoff; verify stage IDs, admitted UUIDs, states, attempts, errors, and reuse/replacement decisions in test assertions and a change-local `investigation.md`, clearly identifying any pre-fix expectations.
- [x] 1.2 Exercise the overlap through a fake-response instrumented scenario using the repository GPU-verified emulator launcher and explicit serial targeting; record observed transitions and whether #111 completion-times recovery remains intact in `investigation.md`, without logging credentials or claiming an unobserved cascade.

## 2. Truthful stage activity and durable observation

- [x] 2.1 Add domain-facing waiting/running/retry-backoff activity snapshots and runner observation callbacks, removing synthetic terminal failure during backoff; verify `WorkStageRunnerTest` covers waiting → running → backoff → success, real failure/cancellation, live work reuse, and persisted exact-ID recovery without duplicate enqueue.
- [x] 2.2 Reconcile attempt-specific library results rather than equating worker SUCCEEDED with a usable library snapshot; verify tests distinguish confirmed empty-library success, private/unreadable library, stale prior error, and account-admission no-op, with actionable stage-specific reporting.
- [x] 2.3 Carry activity through coordinator/repository state and shared checklist/summary UI while preserving outcome codecs and stable stage IDs; verify UI derivation/render tests distinguish every state, indeterminate versus determinate progress, and eventual outcomes after a backoff/process-death recovery.

## 3. Onboarding retry, edited restart, and continue

- [x] 3.1 Add atomic run admission that rejects rapid duplicate start/retry requests before they can wait on the run mutex; verify coordinator tests cover double taps, two surfaces, initial-load/recovery races, and no queued second run.
- [x] 3.2 Add explicit post-settlement selection editing and failed-stage Retry on onboarding, preserving unrelated outcomes and excluding successes from automatic recovery selection; verify ViewModel/Compose tests exercise failure → retry and failure → edit → restart without duplicate successful work or skip-overwrites.
- [ ] 3.3 Keep Continue/exit available during waiting, running, and backoff without cancelling or relabelling active work, and expose that outstanding work on Settings re-entry; verify recreation/process-death tests retain UUID/selection/outcomes and do not reopen dismissed onboarding. Update shared setup copy and recovery documentation with these changes.

## 4. Shared import readiness and integrity

- [x] 4.1 Expose typed import readiness/completion through the profile/history repository boundary and apply the same successful-current-account-snapshot guard in onboarding and Settings; verify unit tests reject unsynced/private libraries, accept a confirmed empty library, preserve existing imported state, and keep UI free of storage imports.
- [x] 4.2 Harden the existing import operation's raw capture and pending-derived recovery at the appropriate transaction/account boundary, following established lock ordering; verify fault-injection tests cover interruption during capture and before/after recomputation, duplicate requests, both sync/import commit orders, monitoring overlap, account change, and unchanged no-double-credit/taper/reset behavior. Document the recovery protocol alongside the operation.

## 5. Optional history decision before Home

- [ ] 5.1 Persist a first-run phase/decision independent of background setup completion and wire credential save, setup continuation/skip, and app takeover routing to it; verify tests cover process death after setup completion, active-legacy-claim migration, no migration takeover for configured users, already-imported state, durable Skip, and later credential editing.
- [ ] 5.2 Add the default-off history decision using the existing import repository operation, explicit Import/Skip, prerequisite explanation, lifetime-total timestamp disclosure, and Retry/Skip on error; verify onboarding tests cover all setup exit paths before Home, no implicit import, success, failure/retry, unavailable baseline, and later Settings availability. Update relevant onboarding/help copy.
- [ ] 5.3 Add bounded screenshot coverage for setup waiting/backoff/failure/recovery and history-choice ready/not-ready/error states, using incumbent visual patterns; inspect narrow/standard and light/dark evidence for action visibility, long-copy wrapping, and accessible non-color state distinctions. Re-record affected tracked goldens in the same checkpoint as fixture-rendered UI changes, and verify the screenshots pass.

## 6. Cross-flow verification

- [ ] 6.1 Run `./gradlew :gamification:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` plus the documented Roborazzi verification and visual-baseline currency check; verify all required checks pass and record exact outcomes and any pre-existing failures separately.
- [ ] 6.2 Re-run the targeted concurrent sync/live-monitoring setup scenario and interrupted/continued onboarding paths, including detached completion times and repeated history import; verify #158 acceptance criteria against recorded stage/work transitions, preserved successes, durable Continue/Skip behavior, and unchanged credited quantities, updating `investigation.md` with final evidence.

## Workflow follow-up

- Autoship task-only coherent checkpoints under the user's authorization, using the optional shipper when available or the skill's verified direct-Git fallback otherwise. A refused checkpoint or platform denial remains a blocker in either mode.
- Review the completed implementation and archive only on a separate user request; sync delta specs as part of that workflow, not by editing normative specs during implementation.
