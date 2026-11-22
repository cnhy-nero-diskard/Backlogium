# Tasks

All implementation and acceptance tasks below target the follow-up on current `master`. [Acceptance coverage](acceptance-coverage.md) records earlier implementation evidence and the current verification plan.

## 1. Confirmed library evidence

- [x] 1.1 Add account-scoped baseline and per-operation commit evidence on the current Room schema. Verify migration preserves imported offsets, recovered activity, cloud receipts, and existing achievement data. Exercise confirmed empty, private/unconfirmed, failed refresh, account reset, and uncertain legacy/restore states.
- [x] 1.2 Bind worker results to the exact admitted account and operation. Verify missing credentials, refused admission, overlapping polls, raw rollback, and interruption after raw commit before output. Assert one tracked credit for the same Steam increase.

## 2. Independent setup-stage recovery

- [x] 2.1 Persist each stage's latest request and exact work association, including immediate completion and KEEP reuse. Verify legacy active-ID migration, interruption before and after enqueue, duplicate taps, missing work, and stale generation or account callbacks.
- [x] 2.2 Bound foreground observation and resume independent selected admissions from durable state. Verify queued work, backoff, running past the wait budget, explicit Continue, and restart with several admitted stages. Assert that pending work remains pending and registry order and successful siblings are preserved.
- [x] 2.3 Update onboarding and Settings projections and affected screenshots. Verify distinct cancellation, per-stage pending/recovery actions, editable next-run selection, a truthful summary, and detached completion-times recovery when notifications are denied.

## 3. Durable history consent and recomputation

- [x] 3.1 Bind consent and first-run exit to the active account and durable request. Verify process recreation before launch, account replacement, Continue while pending, and preservation of existing first-run and later credential-edit behavior.
- [x] 3.2 Apply confirmed readiness and canonical credited-minute capture at the shared import boundary. Verify both entry points, legacy completed imports, the 120-minute unknown-time regression, concurrent sync and import, and preservation of converted offsets and the cloud reset guard.
- [x] 3.3 Carry request, account, and BACKFILL provenance through derived-state recovery. Verify interruption after raw commit, retries, sync and rule changes, and mixed backup/import recovery with one offset capture and no earned-progress event.
- [x] 3.4 Render baseline-required, pending, and completed states in onboarding and Settings. Verify the recovery route, availability of Continue/do later, and absence of an additional crediting action while import is pending.

## 4. Integrated acceptance

- [ ] 4.1 Exercise real manual and periodic Steam polling, live monitoring, and the post-play handoff together on the integrated implementation. Capture redacted stage/work diagnostics and verify reuse, independent completion, and exactly-once credit. Record whether the original cascade is reproducible.
- [x] 4.2 Run the applicable device scenarios in [acceptance coverage](acceptance-coverage.md), covering network changes, denied notifications, process death, and account replacement. Record actual outcomes and evidence boundaries.
- [x] 4.3 Run the relevant host, migration, instrumented, lint, and visual checks against the integrated implementation. Attach current results to the acceptance record and rerun strict OpenSpec validation before sync and archive.
