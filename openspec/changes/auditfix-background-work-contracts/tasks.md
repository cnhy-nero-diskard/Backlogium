## 1. Prerequisites

- [ ] 1.1 **Confirm `auditfix-session-ledger-integrity` has landed and its concurrency tests are green on master.** This change narrows the only cross-worker serialization of the session/daily-progress ledger; narrowing it while #116 is unfixed converts a two-caller race into a five-caller one. Verified by that change's single-open-session test passing on master
- [ ] 1.2 Confirm `auditfix-spec-truth` has landed, since `PostPlaySyncWorker`'s lock sites are reviewed here against the restored targeted-fetch clause (#113)
- [ ] 1.3 Re-read `SteamSyncCoordinator`'s KDoc and record the charter it actually claims — the raw session/daily-progress ledger boundary — as the standard each call site is then judged against. Verified by the charter written down before any site is changed

## 2. Narrow the coordinator (#99)

- [ ] 2.1 Update `SteamSyncCoordinator`'s KDoc to state the narrowed scope and to say explicitly that it is an optimization against redundant Steam requests, not the correctness mechanism — the database is. Verified by the doc matching what the call sites now do
- [ ] 2.2 Narrow `ReconciliationWorker.kt:44` so the library-scale network sweep and achievement merging run **outside** the lock, with only the raw-state read/write regions inside it. Verified by the test in 2.3
- [ ] 2.3 Test: with a reconciliation pass in progress, a periodic or manual sync enters its work and completes without waiting — the regression test for #99 and the spec scenario `steam-achievements/spec.md:112-114`
- [ ] 2.4 Confirm overlap does not let reconciliation and a normal sync refresh the same game's achievements twice in one window. They cover different tiers by design. **If it can, add a per-game guard — do not restore the whole-run lock.** Verified by a test exercising both against one game
- [ ] 2.5 Narrow `SteamSyncWorker.kt:207` the same way, keeping the account-change marker check at the top inside whatever boundary it needs to remain a durable barrier. Verified by the marker still short-circuiting a worker that arrives after an account change
- [ ] 2.6 Review each remaining holder against the narrowed charter and record a keep-or-narrow decision per site: `PostPlaySyncWorker.kt:103,174,243`, `AccountChangeCoordinator.kt:91`, `DailyProgressBackfillUseCase.kt:109`. **`AccountChangeCoordinator:91` is expected to keep its breadth** — it is the identity barrier, and `PostPlaySyncWorker.kt:282` documents a function requiring it. Verified by a decision recorded for all five sites
- [ ] 2.7 **Re-run `auditfix-session-ledger-integrity`'s concurrency tests after the narrowing**, including the case that runs with process-scoped coordination disabled. This is the check that the upstream guarantee holds under the wider concurrency this change creates. Verified by those tests passing unchanged
- [ ] 2.8 Test: manual **Sync now** during a reconciliation pass completes without waiting for library-scale work — `steam-sync/spec.md:52-73`

## 3. Trigger identity (#107)

- [ ] 3.1 Add a trigger identity to the `SteamSyncWorker` input data, and set it from `SyncScheduler.syncNow()` (`:178-187`) as player-initiated and from the periodic path as periodic. **Do not infer manual from the `ONE_TIME_NAME` work name** — that is a coincidence, not a contract (design.md Decision 2). Verified by both paths setting it explicitly
- [ ] 3.2 Keep `UNIQUE_PERIODIC_NAME` and `ONE_TIME_NAME` separate. `SyncScheduler.kt:173-177` documents why, and the archived `auditfix-sync-write-integrity` rejected merging them for this same worker pair. Verified by both constants still present and distinct
- [ ] 3.3 Replace `SteamSyncWorker.kt:218`'s `diagnostics.begin(if (runAttemptCount > 0) "retry" else "scheduled")` with the trigger from input data, recording `runAttemptCount` as a separate attribute. Verified by the test in 3.5
- [ ] 3.4 Extend `SyncRunRecorder` so a record carries trigger **and** attempt rather than one field doing both duties. Verified by the diagnostics surface showing both for a retried run
- [ ] 3.5 Test: a manual sync records a player-initiated trigger; a periodic sync records periodic; a retried manual sync records manual **and** a retry attempt — the three cases the current single expression conflates
- [ ] 3.6 Confirm the post-play trigger and its scoped game id are unaffected, since `app-diagnostics` already requires those and they must keep working. Verified by the existing post-play diagnostics tests passing
- [ ] 3.7 Check the diagnostics surface in `ui/diagnostics/` renders the new trigger and attempt legibly. Per `CLAUDE.md` that package reads `DiagnosticsDao` directly and renders rows verbatim, so a new field needs no mapping layer — but it does need to be readable

## 4. Durable completion-times stage (#111)

- [ ] 4.1 Add a worker that performs the HLTB dataset check and apply currently inlined in `SetupStageRegistry`'s `SetupStageRunner`, reporting progress through worker progress data the way the artwork worker does. Verified by the worker running standalone and reporting progress
- [ ] 4.2 Switch the completion-times stage to `WorkStageRunner(workManager, uniqueWorkName, trigger, progressOf, failureReason)`, matching the artwork stage. Verified by the stage producing a non-null work id
- [ ] 4.3 **Do not change `STAGE_COMPLETION_TIMES = "completion_times"`.** `SetupStage`'s KDoc warns that renaming a persisted stage id orphans every user's stored opt-in and outcome. Verified by the constant unchanged
- [ ] 4.4 Give the worker its own ongoing notification, as the `DETACHED` contract requires and the artwork stage already provides. Verified on a device by the notification appearing after leaving setup
- [ ] 4.5 Test: the stage's progress is observable after process death and `recoverRun()` reattaches rather than restarting, because the persisted active marker now has a non-null `workId` — the regression test for #111
- [ ] 4.6 Test: leaving the setup surface mid-stage does not cancel it, and re-entering setup shows the in-progress stage rather than offering it fresh
- [ ] 4.7 Confirm `defaultOptIn = true` still holds and the stage still runs for a new user by default — the behaviour that de-declaring `DETACHED` would have regressed (design.md Decision 3). Verified by a first-run pass on a device
- [ ] 4.8 Confirm the other stages are unaffected: library sync stays `IN_SCREEN`, artwork stays `DETACHED` with its existing worker, and a failing completion-times stage still does not affect the others per `first-run-setup/spec.md:133`

## 5. Close out

- [ ] 5.1 `openspec validate --strict auditfix-background-work-contracts` passes
- [ ] 5.2 `./gradlew :gamification:test :app:testDebugUnitTest` passes
- [ ] 5.3 On a device: start a reconciliation pass, tap **Sync now** during it, and confirm from the diagnostics surface that the manual run completed without waiting and is recorded as manual — #99 and #107 verified together on the real thing
- [ ] 5.4 On a device: opt into completion times, leave setup, force-stop the app, reopen, and confirm the stage continued rather than restarting
- [ ] 5.5 Sync the delta into `openspec/specs/` via the archive workflow, not by hand
- [ ] 5.6 Close #99, #107, #111
