## Why

**Branch: `fix/auditfix-background-work-contracts`**

Three audit findings where background work violates a contract its own spec already states.
In all three the spec is right and the code is wrong, so this change is almost entirely
implementation — the one delta below strengthens a clause that turned out to be readable
two ways.

**#99 — reconciliation blocks periodic and manual sync (`severity/high`).**
`steam-achievements/spec.md:112-114` requires that while a reconciliation pass is in progress
"a periodic or manual sync can still run and complete without waiting for it", and
`steam-sync/spec.md:52-73` independently requires manual **Sync now** to complete without
waiting for library-scale work. Both workers take the same process-wide mutex around their
entire run: `ReconciliationWorker.kt:44` wraps `doWorkLocked()` in
`syncCoordinator.withLock`, and `SteamSyncWorker.kt:207` does the same. A reconciliation
sweep can cover many cold-tier games and run for minutes; while it owns the mutex the normal
sync cannot enter its work at all. The whole reason reconciliation exists as a separate
deferred pass is to keep library-scale work out of the latency-sensitive sync path, and the
lock defeats that.

**#107 — manual sync runs are recorded as scheduled.** `SyncScheduler.syncNow()`
(`:178-187`) enqueues a `SteamSyncWorker` one-time request carrying no trigger identity, so
`SteamSyncWorker.kt:218` records `diagnostics.begin(if (runAttemptCount > 0) "retry" else
"scheduled")` — a user-initiated sync is persisted as **scheduled**, and a retry loses
whether its originating run was manual or periodic. Diagnostics exist specifically to make
otherwise-similar runs attributable, so this is the one field whose wrongness makes the
record misleading rather than merely incomplete.

**#111 — the detached completion-times stage is not durable.**
`first-run-setup/spec.md:106+` requires every `DETACHED` stage to continue after the user
leaves setup, own its progress notification, **survive process death with progress still
observable**, and stay recoverable independently of the setup surface. The artwork stage
satisfies this via `WorkStageRunner(workManager, uniqueWorkName, …)`. The completion-times
stage declares `execution = SetupStageExecution.DETACHED` but runs a plain
`SetupStageRunner { … hltbDatasetRepository.checkAndApply(…) }` — no WorkManager job, so no
durable work id and no worker-owned notification. `SetupCoordinator` launches it in the
application scope, which outlives a Compose surface but not the process, and `recoverRun()`
can only reattach when the persisted active marker has a non-null `workId`. For this runner
that id is null, so a cold restart *starts the stage again* rather than continuing it. It is
detached from the UI, not from the process.

## What Changes

- **Coordination narrows to the boundaries that need it.** The mutex stops wrapping whole
  worker runs and moves to the raw-state read/write boundary it was created for — its own
  KDoc says it "serializes operations that read or write the raw session/daily-progress
  ledger", not "serializes entire workers". Reconciliation's library-scale network sweep runs
  outside it, so a periodic or manual sync can enter and complete during a pass.
- **Trigger identity travels with the work request.** `syncNow()` marks its request as
  manual; the periodic path marks itself periodic; `SteamSyncWorker` records that identity
  instead of assuming `"scheduled"`. Retry becomes an *attribute* of a run rather than a
  replacement for its trigger, so a retried manual sync is still recorded as manual.
- **The completion-times stage becomes genuinely durable.** It gains a WorkManager job and
  moves to `WorkStageRunner`, matching the artwork stage, so it has a work id to reattach to
  and a worker-owned notification. `design.md` Decision 3 explains why this rather than
  dropping the `DETACHED` declaration — the stage is `defaultOptIn = true`, so de-declaring
  would quietly regress a behaviour every new user gets by default.
- Spec-level: `app-diagnostics` gains explicit language that periodic and manual runs are
  distinguishable **from each other**, and that retry state does not replace trigger
  identity. The current clause requires each record to identify "what triggered it" and then
  only spells out post-play versus "periodic or manual" as a group, which is what let the
  implementation collapse the two.

**BREAKING (behavioural, not schema)**: reconciliation and a normal sync may now genuinely
overlap. That is the fix, but it is a real concurrency increase against the session and
daily-progress ledger, which is why the dependency below is a hard one rather than a
courtesy.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `app-diagnostics`: a persisted run record distinguishes periodic from manual, and retry
  state does not overwrite the originating trigger

`steam-achievements`, `steam-sync`, and `first-run-setup` need no delta — their existing
requirements already state the correct behaviour, and this change makes the implementation
meet them.

## Impact

| Path | Change |
|---|---|
| `work/SteamSyncCoordinator.kt` | scope documented and narrowed to the ledger boundary |
| `work/ReconciliationWorker.kt` | library-scale sweep no longer inside the lock (`:44`) |
| `work/SteamSyncWorker.kt` | lock narrowed (`:207`); trigger from input data (`:218`) |
| `work/SyncScheduler.kt` | `syncNow()` and the periodic path pass trigger identity (`:178-187`) |
| `work/PostPlaySyncWorker.kt` | four `withLock` sites reviewed against the narrowed scope |
| `data/repo/AccountChangeCoordinator.kt` | `:91` — the account-change barrier keeps its breadth |
| `domain/DailyProgressBackfillUseCase.kt` | `:109` — reviewed against the narrowed scope |
| `…/SetupStageRegistry.kt` | completion-times moves to `WorkStageRunner` |
| `…/` (new worker) | durable HLTB dataset check/apply job |
| `data/diagnostics/SyncRunRecorder.kt` | trigger plus attempt, not trigger *or* attempt |

**Hard dependency on `auditfix-session-ledger-integrity`.** This change narrows the only
cross-worker serialization of the session and daily-progress ledger. Five callers take that
mutex today — `SteamSyncWorker:207`, `ReconciliationWorker:44`, `PostPlaySyncWorker:103/174/243`,
`AccountChangeCoordinator:91`, `DailyProgressBackfillUseCase:109` — while the presence path
(`PresenceSessionRecorder`) takes it not at all, which is #116. Narrowing before #116's
write-boundary guarantee exists converts a two-caller race into a five-caller one. **Do not
start this change with #116 unfixed.**

**Depends on `auditfix-spec-truth`** for the `steam-sync` targeted-fetch clause (#113), since
`PostPlaySyncWorker`'s lock sites are reviewed here against that requirement.

**Deliberately preserved**: `UNIQUE_PERIODIC_NAME` and `ONE_TIME_NAME` stay separate work
names. `SyncScheduler.kt:173-177` documents why, and the archived
`auditfix-sync-write-integrity` rejected merging them for the same reason — unique-work names
are one namespace, a periodic request sits `ENQUEUED` almost permanently, and `KEEP` on a
shared name would drop nearly every manual sync. Trigger identity is carried in input data
*because* the names must stay separate.

**Not addressed here**: the account-change barrier's breadth. `AccountChangeCoordinator:91`
deliberately holds the lock across a reset, and `PostPlaySyncWorker:282` documents a function
that must be called while holding it. Narrowing those is not required by any finding and
would risk the identity barrier that `auditfix-account-identity` established.
