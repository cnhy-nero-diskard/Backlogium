## 1. Milestone A - Characterize concurrent setup and recovery

- [x] 1.1 Establish reproducible tests for retry-backoff misclassification, hidden onboarding Retry, and completed-selection masking; verify each fixture fails against the existing behavior for the intended reason.
- [ ] 1.2 Exercise setup with running manual/periodic Steam work and live monitoring/post-play handoff; verify a diagnostic record captures stage/work identities, scheduler states, errors, and reused-versus-new admission without secrets or raw account identifiers, and records any unreproduced report honestly.
- [x] 1.3 Compare the active presence-attribution deltas and current cloud imported-play transfer/reset behavior with this change; verify a short implementation overlap map identifies shared files without conflicting requirement names or a duplicate session author.

## 2. Milestone A - Attributable sync results and baseline readiness

- [x] 2.1 Define domain library-poll result and readiness models exposed through repositories, keeping entities out of UI; verify mapper tests distinguish committed, not-performed, recoverable failure, unknown evidence, and confirmed empty.
- [x] 2.2 Add minimal account-scoped confirmed-baseline persistence and its Room migration, preserving field-scoped profile writes; verify migration tests initialize uncertain legacy data as unconfirmed while preserving existing imports, offsets, sessions, and cloud receipts.
- [x] 2.3 Record baseline confirmation in the accepted raw library transaction and clear it on account reset; verify real-database tests cover confirmed zero games, rejected/private responses, rollback during first commit, and a later failed refresh retaining readiness.
- [x] 2.4 Publish operation results attributable to exact work/account identity, including crash-after-raw-commit evidence; verify missing credentials, account admission refusal, privacy/unconfirmed response, overlapping polls, and raw-commit-before-output cases cannot produce a false setup success.
- [x] 2.5 Keep new local confirmation and transient work identities out of unverified backup restoration; verify backup/restore and account-boundary tests preserve trusted local readiness without granting readiness from uncertain restored rows or timestamps.

## 3. Milestone A - Durable stage ownership and foreground settlement

- [x] 3.1 Introduce per-stage operation states and a separate foreground-attempt projection; verify pure state tests distinguish waiting/running/retry scheduled from succeeded/failed/cancelled/skipped and never infer terminal failure from busy work or elapsed time.
- [x] 3.2 Extend the setup store with versioned latest-attempt records, cohort selection, account ownership, and exact request/work associations; verify round-trip and legacy-migration tests retain known outcomes, ignore unknown stages, and do not fabricate live jobs.
- [x] 3.3 Add an admission-handle seam around existing schedulers, persisting intent before enqueue and reconciling new versus KEEP-reused work after admission; verify WorkManager tests cover immediate completion, existing live work, enqueue/identity-write interruption, duplicate taps, and stale finished records.
- [x] 3.4 Replace terminal-backoff observation with live per-stage reconciliation; verify retry scheduled can later become succeeded, HLTB worker failure reasons remain attributable, cancellation remains distinct, and missing/pruned jobs offer explicit recovery rather than loop or claim success.
- [x] 3.5 Refactor foreground observation to settle on queued/backoff admission, detached admission, terminal result, explicit Continue, or the injected monotonic 120-second budget; verify independent selected stages are admitted in order and budget expiry never cancels or fails work.
- [x] 3.6 Fence stage callbacks by attempt generation, work identity, and account; verify old callbacks and old-account completions cannot overwrite a replacement attempt, and concurrent periodic/manual commits still credit a playtime increase exactly once.
- [x] 3.7 Load and reconcile saved setup work at application startup after account-change recovery and on Settings entry; verify cold-start tests resume each pending association and remaining selected admissions without duplicates or reopening explicitly dismissed onboarding.

## 4. Milestone A - Non-destructive recovery UI

- [x] 4.1 Separate active-run selection from editable next-run selection in the shared ViewModel/projection; verify visible checkbox changes after settlement exactly match the next start request and editing is blocked only for the foreground attempt.
- [x] 4.2 Expose onboarding and Settings per-stage Retry/Run again/progress affordances with coordinator-level duplicate-admission protection; verify Compose tests cover a failed-stage retry preserving successful siblings and reattaching pending work without promising immediate execution.
- [x] 4.3 Render truthful waiting/backoff/cancelled/recovery-required states and pending summaries with accessible labels and localized copy; verify UI tests never show all-work completion for pending jobs and progress is determinate only with a real usable total.
- [x] 4.4 Preserve explicit Continue/do-later during foreground work, independent selected-stage admission after exit, and worker-owned detached notifications; verify navigation and notification-permission tests cover exit before start, exit mid-run, and denied notification permission without relabelling results.
- [x] 4.5 Verify independent HLTB acquisition and zero-inventory artwork behavior when library sync is pending; verify tests show a stored dataset and an honest zero-available-items artwork result/re-run explanation rather than a cascading failure or an invented populated-library download.

## 5. Milestone B - Shared safe history-import operation

- [x] 5.1 Replace the Boolean import result and UI-only busy flags with shared domain import/request state for onboarding and Settings; verify repository tests cover explicit consent, concurrent request coalescing, Already imported, Needs baseline, pending recomputation, failure, and superseded account.
- [x] 5.2 Guard import at its shared commit boundary using same-account baseline evidence and the account-change admission marker; verify no-baseline requests write nothing, confirmed empty imports report zero changes, later refresh failures do not revoke readiness, and completed legacy imports remain completed.
- [x] 5.3 Coordinate import and reset in sync -> derived -> Room order and atomically snapshot/apply offsets, the one-time flag, and pending recomputation; verify real-database rollback tests and concurrent sync/import tests prove coherent minutes and no deadlock or partial flag commit.
- [x] 5.4 Preserve existing shared-to-owned converted credits, manual/shared minutes, hidden-game evidence, tracked sessions, and taper inputs while tightening import; verify dedicated fixtures prove no credit is erased or double-counted and no dated session, daily quest credit, or streak is invented from lifetime counters.
- [x] 5.5 Persist explicit import-request identity and BACKFILL recomputation provenance and extend startup recovery through the existing derived/progress protocol; verify kills before launch, before raw commit, after raw commit, and during recomputation recover without refreezing totals or emitting imported XP as earned progress.
- [x] 5.6 Integrate pending administrative recomputation with every marker-clearing derived writer and mixed backup/history recovery; verify an intervening sync or rule change cannot clear the marker with stale derived values or create duplicate/earned import events.
- [x] 5.7 Preserve repeated-import, growing-total, reset, and cloud transferred-play safeguards under the shared coordinator; verify repeats do not add offsets, tracked play continues normally, reset preserves sessions, and an applied imported-play transfer still blocks reset until reversal.

## 6. Milestone B - Durable optional decision before Home

- [x] 6.1 Introduce account-scoped first-run phases with a compatible migration from the existing takeover claim; verify migration tests map an old unfinished claim to setup, leave configured installs without a claim out of onboarding, and preserve terminal historical results.
- [x] 6.2 Route completed/continued/declined setup into the history choice without repeating credentials or changing the credential step count; verify ViewModel/navigation tests cover all three transitions and background setup completion cannot clear an unanswered decision.
- [x] 6.3 Add the incumbent-style history-choice surface with explicit Import, Skip/do later, readiness explanation, Already imported, and a setup-recovery route; verify Compose tests prove no implicit consent or import and that both ready and unavailable-baseline users can reach Home.
- [x] 6.4 Implement durable explicit exit and cold-launch phase restoration, including Continue/do later during pending import and Back to setup review; verify process-recreation tests keep admitted work recoverable, do not re-open a deferred journey, and never advance a replacement account from an old request.

## 7. Milestone B - Settings parity and startup recovery

- [x] 7.1 Adapt Settings -> Data & privacy to the shared import state, baseline explanation/recovery route, pending-recompute feedback, and existing confirmation/reset controls; verify Settings tests cover a skipped first-run choice, blocked premature import, pending recovery, and unchanged cloud reversal-before-reset behavior.
- [x] 7.2 Integrate first-run phase and import-request reconciliation with the Home takeover and application startup after account recovery; verify cold launches on unanswered choice, consent-before-launch, raw-commit-before-recompute, and explicit exit restore the correct surface without credential re-entry or a Home flash.

## 8. End-to-end acceptance and release evidence

- [x] 8.1 Run `./gradlew.bat :gamification:test :app:testDebugUnitTest` and focused migration/WorkManager/UI suites; verify all pass and every scenario in the five delta specs has an automated test or a named manual acceptance check.
- [x] 8.2 Re-record and verify affected Roborazzi goldens using `docs/visual-regression-screenshots.md`, keeping baseline changes with the UI changes; verify onboarding/setup/Settings/Home states and changed strings do not leave stale golden coverage.
- [ ] 8.3 Exercise a debug build on device with manual/periodic sync, active play/live monitoring, airplane-mode transitions, retry backoff, denied notifications, and process death before/after Continue and import commit; verify delivered evidence demonstrates truthful states, available exit/recovery, and exactly-once imported/tracked credit without claiming an unreproduced cascade.
- [x] 8.4 Run `openspec validate stabilize-first-run-setup --strict` and review the final diff against the issue's F06/F32 acceptance criteria and the overlap map; verify no #159/#175 achievement work, main-spec edits before sync/archive, new cloud dependency, or unrelated implementation is bundled, and identify any unmet device acceptance criterion before closing #158.
