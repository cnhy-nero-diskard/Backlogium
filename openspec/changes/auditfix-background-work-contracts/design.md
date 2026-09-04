## Context

See `proposal.md` — Why. Three findings where a worker breaks a contract its spec already
states.

The lock's actual purpose, from its own KDoc:

> The mutex serializes operations that read or write the raw session/daily-progress ledger in
> this process. Database commits still re-read their baselines, because WorkManager may run
> work in another process in a future build and tests must not need this lock to prove that
> concurrent observations cannot double-count.

That is a narrow, well-argued charter. Both workers then apply it to their whole `doWork()`,
so it also serializes network sweeps, achievement merges, and everything else — which is #99.
The gap is between what the KDoc claims and what `withLock` actually encloses, not in the
design of the coordinator.

Current holders, all of which this change reviews:

| Site | Held across | Verdict |
|---|---|---|
| `SteamSyncWorker.kt:207` | whole run | narrow |
| `ReconciliationWorker.kt:44` | whole run | narrow |
| `PostPlaySyncWorker.kt:103,174,243` | three scoped regions | review, likely keep |
| `AccountChangeCoordinator.kt:91` | account reset | **keep** — identity barrier |
| `DailyProgressBackfillUseCase.kt:109` | ledger snapshot + correction | review, likely keep |
| `PresenceSessionRecorder` | *nothing* | this is #116, fixed upstream |

## Goals / Non-Goals

**Goals:**

- A reconciliation pass in progress does not prevent a periodic or manual sync from running
  and completing.
- A manual sync is recorded as manual, and stays manual across retries.
- The completion-times stage satisfies the `DETACHED` contract it declares.

**Non-Goals:**

- Redesigning the coordinator. Its charter is right; the call sites misapply it.
- Merging the periodic and one-time work names. Rejected before, for a documented reason
  (see the proposal), and trigger identity in input data is the consequence of keeping them
  separate.
- Narrowing the account-change barrier at `AccountChangeCoordinator:91`. No finding asks for
  it and `auditfix-account-identity` established it deliberately.
- Guaranteeing session-write safety. That is `auditfix-session-ledger-integrity`, and this
  change **depends** on it rather than reproducing it.

## Decisions

### Decision 1: Narrow the lock to the ledger boundary; do not replace it with a work-name merge

The mutex moves from `doWork()` to the raw-state read/write regions, so reconciliation's
network sweep and achievement merging run outside it.

**Alternative considered**: serialize the two workers by giving them a shared unique work
name and letting WorkManager arbitrate. Rejected, and this is settled precedent rather than a
fresh judgement — `SyncScheduler.kt:173-177` documents the hazard, and the archived
`auditfix-sync-write-integrity` rejected it explicitly for this same worker pair: unique-work
names live in one namespace, the periodic request sits `ENQUEUED` almost permanently, and
`KEEP` against a shared name would drop nearly every manual sync, including while idle.

**What makes narrowing safe.** Not the lock — the database. The coordinator's KDoc already
states the intended division: commits re-read their baselines, so correctness survives the
lock being absent, and the lock exists to stop redundant work from spending Steam requests.
`auditfix-session-ledger-integrity` extends that same property to the session write itself
(one open session per game, enforced where a concurrent caller cannot race past it). Once
that holds, the lock is genuinely an optimization and can be scoped like one. Before it
holds, it is load-bearing by accident — which is the whole reason for the ordering.

### Decision 2: Trigger identity in input data, with retry as a separate attribute

`SteamSyncWorker.kt:218` currently computes trigger and retry from the same expression:
`if (runAttemptCount > 0) "retry" else "scheduled"`. Two problems in one line — it guesses
`"scheduled"` for manual runs, and it lets a retry erase whatever the trigger was.

The request carries the trigger; the worker reads it and records it alongside
`runAttemptCount`. So a retried manual sync records *manual, attempt 2* rather than
*"retry"*.

**Alternative considered**: infer manual from the work name, since `syncNow()` uses
`ONE_TIME_NAME`. Rejected — it is a coincidence rather than a contract. A one-time request
could be enqueued for reasons other than a player tapping Sync now (a future first-run or
post-restore trigger), and the inference would silently misattribute it. Input data states
the fact instead of deriving it from a naming convention.

The `app-diagnostics` delta exists because the current wording — "what triggered it", then
post-play versus "periodic or manual" as a group — can be read as satisfied by lumping the
two together. Narrowing the text is what stops this regressing.

### Decision 3: Give completion-times a real worker rather than dropping `DETACHED`

The audit offered both. A worker is chosen.

**Why not de-declare to `IN_SCREEN`.** It is the one-line fix and it makes the code honest,
which is genuinely tempting. But the stage is `defaultOptIn = true` — every new user gets it
— and it downloads the shared HowLongToBeat dataset, which is what populates completion
times for most of a library. Making it in-screen means a user who opts into setup and then
leaves the screen loses it, and `personal-pace-forecasting` and the XP taper both degrade
quietly for want of a dataset the user was told they were getting. De-declaring would trade a
correctness bug for a product regression, and would be recorded as "resolved".

**The shape.** Follow the artwork stage exactly: a worker with a unique work name, plus
`WorkStageRunner(workManager, uniqueWorkName, trigger, progressOf, failureReason)`. That
gives the non-null `workId` `recoverRun()` needs, a worker-owned notification, and progress
observable across process death — the three obligations the spec names. It is a known-good
pattern in this file rather than a new mechanism.

**Note on the stage id.** `STAGE_COMPLETION_TIMES = "completion_times"` is persisted, and
`SetupStage`'s KDoc warns that renaming one orphans every user's stored opt-in and outcome.
The id does not change here.

### Decision 4: Review the other three lock holders rather than assuming they are fine

`PostPlaySyncWorker` (three sites) and `DailyProgressBackfillUseCase` already hold the lock
around scoped regions rather than whole runs, so they are probably already correct — but
"probably" is not enough when the change narrows the shared invariant they were written
against. `PostPlaySyncWorker.kt:282` documents a function that "must be called while holding
`syncCoordinator`, which is the account-change barrier lock", so at least one site depends on
the lock for a *second* purpose beyond the ledger. Task 2.6 checks each against the narrowed
charter explicitly, and `AccountChangeCoordinator:91` is expected to keep its breadth for
exactly that reason.

## Risks / Trade-offs

**Narrowing the lock increases real concurrency against the ledger** → The entire reason for
the dependency on `auditfix-session-ledger-integrity`. Task 1.1 gates on it, and task 2.7
re-runs that change's concurrency tests after the narrowing to prove the guarantee still
holds under the wider concurrency this change creates. If those tests were ever going to be
worth having, it is here.

**The account-change barrier could be narrowed by accident** → It is a distinct purpose
sharing one mutex, which is the kind of thing a mechanical narrowing breaks. Decision 4 and
task 2.6 make each site an explicit decision; `AccountChangeCoordinator:91` and
`PostPlaySyncWorker:282` are called out by line.

**A new worker for completion-times adds a scheduling surface** → It follows the artwork
stage's established pattern rather than inventing one, and reuses the existing
`WorkStageRunner` plumbing, so the new surface is a worker class rather than a new mechanism.

**Reconciliation and sync overlapping could double-spend Steam requests** → They cover
different tiers by design (reconciliation is the cold tier). Task 2.4 confirms overlap does
not cause the same game to be refreshed twice in one window; if it can, the answer is a
per-game guard, not restoring the whole-run lock.

## Migration Plan

1. `auditfix-session-ledger-integrity` must be merged. Verify its concurrency tests are green
   on master first.
2. Trigger identity (#107) first — self-contained, no concurrency implications, and it makes
   the diagnostics surface useful for observing steps 3 and 4.
3. Completion-times worker (#111) — independent of the lock work entirely.
4. Lock narrowing (#99) last, with the upstream concurrency tests re-run after it.

Rollback: steps 2 and 3 are ordinary code reverts. Step 4's revert restores the whole-run
lock, which is safe but reinstates #99 — so revert it only as a unit, never partially, or
some workers will hold a narrow lock while others hold a broad one.
