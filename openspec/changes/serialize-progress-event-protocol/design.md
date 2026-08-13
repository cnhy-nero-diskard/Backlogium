## Context

After `harden-progress-events-durability`, `GamificationUpdater.persist()` runs a six-phase
protocol across two storage engines:

```
resolve prior pending transition      (DataStore read, maybe DataStore write)
capture previous state                (Room reads)
write pending-transition WAL          (DataStore write)
perform Room derived-value writes     (Room writes)
finalize progress marks               (DataStore write)
clear WAL                             (same DataStore write as finalize)
```

Every individual step is atomic. `ProgressMarksStore.update {}` is one DataStore `edit {}`
transaction, and each Room write commits on its own. What is *not* atomic — and what nothing
currently prevents from interleaving — is the sequence. Between phase 3 and phase 5 the WAL and Room
deliberately disagree: that disagreement is the recovery information, and it is also a state a
second participant cannot interpret.

Two participants exist today. `resolvePendingTransition()` is called by
`ProgressEventRepository.pendingEvents` at flow start, and `persist()` is called by the sync worker,
the rule-change use case, the backfill use case, and the restore engine. There is no ordering
between them.

The quest side has a different problem, not a concurrency one. `pendingEvents` reconstructs
`QuestMet` from stored state:

```kotlin
days.filter { it.questMet }
    .map { LocalDate.parse(it.date) }
    .filter { acknowledgedThrough == null || it > acknowledgedThrough }
    .minOrNull()
```

`DailyProgress.questMet` is a *derived* value, recomputed against the current `RuleConfig` on every
pass. It answers "does this day's playtime satisfy today's rule", which is not the question delivery
needs answered ("did the player earn this transition while the feature was watching, and has anyone
shown it to them"). And `ProgressEventDetector.reseed()` sets
`lastQuestCelebratedDate = today.takeIf { current.todayQuestMet }` — so a non-earned recompute on a
day whose quest is unmet nulls the mark, and every historical met row instantly qualifies again.

## Goals / Non-Goals

**Goals:**
- A recovery pass can never clear, consume, or misattribute the recovery state of a `persist()` that
  is still running, and two `persist()` calls with different provenance can never affect each
  other's.
- No consumer ever derives an event by comparing a Room value against a mark that describes a
  different logical version of state.
- Quest delivery is decided by explicitly recorded earned identity, never by re-reading
  `DailyProgress`. The four cases (earned by `SYNC`; recomputed met by `RULE_CHANGE`/`BACKFILL`/
  `RESTORE`; historical rows predating tracking; already acknowledged) are distinguishable.
- Everything the previous change established is preserved: required `RecomputeSource`, `compute()`
  side-effect freedom, atomic `ProgressMarksStore.update`, durable `PendingTransition` recovery for
  `StreakBroken`, presentation priority, and Home's durable milestone consumption.

**Non-Goals:**
- Cross-process serialization. All persistence paths run in the app process (WorkManager workers
  included); the coordinator is deliberately a process-local mutex, not a file lock.
- Cross-store (Room + DataStore) atomicity. Unchanged from the previous design: the WAL plus
  idempotent recovery achieves the needed guarantee without a joint transaction the engines don't
  support.
- Making the per-row `DailyProgress` upsert loop transactional.
- Delivering more than one `QuestMet` at a time. The pending set can hold many; the repository still
  surfaces the oldest, as the spec already permits.

## Decisions

### 1. One process-wide coordinator around the whole protocol, not tighter individual writes

```kotlin
@Singleton
class ProgressTransitionCoordinator @Inject constructor() {
    private val mutex = Mutex()
    suspend fun <T> withTransition(block: suspend () -> T): T = mutex.withLock { block() }
}
```

`persist()` becomes `withTransition { persistWithinProtocol(result, source) }`, and
`resolvePendingTransition()` becomes `withTransition { resolvePendingTransitionWithinProtocol(...) }`.

The hazard being fixed is the interleaving of phases, so the unit of mutual exclusion is the phase
sequence. Alternatives considered and rejected:

- **Making the WAL multi-slot** (one record per in-flight persist, keyed by an id). This lets two
  persists coexist without destroying each other's records, but does nothing about either one
  capturing its "previous state" from a Room row the other is mid-way through replacing — the
  captured previous state would be a value no consistent version of the profile ever had. It also
  leaves recovery unable to tell a live record from an abandoned one, which is the primary defect.
- **Comparing timestamps or a liveness heartbeat on the WAL** ("assume abandoned if older than N
  seconds"). Turns a correctness property into a timing guess, and picks the wrong answer under a
  slow disk or a stopped debugger.
- **A no-op recovery when the process wrote the record itself** (an in-memory "mine" flag). Works
  for the recovery-vs-persist race only, and is silently wrong for persist-vs-persist.

Reentrancy: `Mutex` is not reentrant, and `persist()`'s first phase *is* recovery. Hence the
`…WithinProtocol` split — the public wrapper acquires, the internal function assumes ownership.
That split is load-bearing, not stylistic: calling the public form from inside the protocol
deadlocks.

Because the coordinator must be one instance to mean anything, it is injected as a `@Singleton` and
threaded explicitly through `ProgressEventRepository`'s constructor. `GamificationUpdater` keeps a
default-constructed instance for the many tests that drive derived values and ignore events,
matching the existing `progressMarksStore` default; any test that shares state between an updater
and a repository must pass the same instance to both.

Failure handling: a pending transition suppresses derivation (decision 2), so a `persist()` that
throws *after* writing its WAL must not return with the record still in place — that would freeze
delivery for the rest of the process lifetime, which is a worse failure than the one being reported.
`persistWithinProtocol` therefore resolves its own record in a `NonCancellable` `catch` before
rethrowing. The WAL is for surviving process death, not for outliving a caught exception.

### 2. A pending transition makes the Room/marks pair non-derivable

`pendingEvents` skips the two *reconstructed* comparisons (`LevelUp` from `profile.level` vs
`lastCelebratedLevel`, `StreakMilestone` from `profile.currentStreak` vs
`lastCelebratedStreakMilestone`) whenever `marks.pendingTransition != null`. It keeps emitting the
two *durable* slots (`pendingQuestDates`, `pendingStreakBreak`), because those are written only by
the finalize step and therefore still hold their last finalized, self-consistent value during an
in-flight transition. Suppressing them would delay a delivery already owed for no gain.

This is a guard on the derivation, deliberately not a lock held across it: taking the coordinator on
every combine tick would make a Compose collector wait on a background sync's Room writes. The flow
re-emits as soon as finalization changes the marks, so no signal is lost by declining to derive.

`resolvePendingTransition()` still runs once at flow start, so an *abandoned* record from a previous
process is resolved rather than suppressing derivation forever.

### 3. `pendingQuestDates` — explicit earned identity, edge-triggered

```kotlin
data class ProgressMarks(
    ...
    val pendingQuestDates: Set<LocalDate> = emptySet(),
)
```

Stored as one `stringSetPreferencesKey("pending_quest_dates")` of ISO dates, decoded oldest-first so
delivery order is a property of the stored value rather than of whoever iterates it. A `Set` rather
than an ordered list: the identity is the date, duplicates are meaningless, and the ordering the spec
cares about (oldest first) is derivable from the dates themselves.

A date is added only by `ProgressEventDetector.detect()` under `RecomputeSource.SYNC`, and only when
the recompute *flips* the day:

```kotlin
if (!current.todayQuestMet || previous.todayQuestMet) return null   // edge-triggered
if (today in marks.pendingQuestDates) return null                   // already owed
if (acknowledgedThrough != null && today <= acknowledgedThrough) return null  // already shown
return today
```

Edge-triggering on `previous.todayQuestMet` is the same shape `StreakBroken` already uses, and it is
what makes "earned" mean a transition rather than a state: a second sync on the same day sees the
flag already set and earns nothing. The two guards below it make the operation idempotent under
recovery replay and prevent a re-met acknowledged day from replaying.

Provenance is applied at the mutation, not filtered afterwards:

| source | pending set | `lastQuestCelebratedDate` | events |
|---|---|---|---|
| `SYNC`, newly earned | add today | unchanged | `QuestMet(today)` |
| `SYNC`, already met | unchanged | unchanged | none |
| `RULE_CHANGE`/`BACKFILL`/`RESTORE` | **unchanged** | advanced to today if today is met, never regressed | none |
| first initialization | seeded **empty** | today if today is met | none |

Two consequences worth stating explicitly. Non-earned sources leave the pending set alone in *both*
directions — a recomputed-met historical row is not an earned quest, and a quest earned before the
rule change is still owed a delivery the rule change has no business cancelling. And
`lastQuestCelebratedDate` is now monotonic (`laterOf`), so no baseline reset can make acknowledged
history deliverable again. First initialization seeding empty is what keeps an account that has been
meeting its quest for a year from being handed a year of celebrations.

Acknowledgement removes exactly the acknowledged date and advances the high-water mark:

```kotlin
marks.copy(
    lastQuestCelebratedDate = laterOf(marks.lastQuestCelebratedDate, event.date),
    pendingQuestDates = marks.pendingQuestDates - event.date,
)
```

Set subtraction of an absent element is a no-op, so a duplicate acknowledgement cannot consume a
different pending date. Acknowledging out of order is safe in both fields.

Migration: existing installs decode an absent key as an empty set, so nothing already met and
un-celebrated becomes pending on upgrade. That is the intended reading of "historical rows are
evidence of nothing" — the alternative (seeding the set from met rows) would celebrate history on
first launch after the update.

## Risks / Trade-offs

- **Serializing the protocol serializes persists.** A rule change confirmed while a background sync
  is persisting now waits for it. The critical section is a handful of Room writes and two DataStore
  edits; the wait is bounded by that, and the previous behaviour in that window was a lost or
  misattributed transition rather than a fast one.
- **The coordinator is only correct as a singleton.** Two instances are indistinguishable from no
  coordination. Enforced by DI in production; in tests it is a wiring discipline, documented on the
  `GamificationUpdater` constructor parameter and centralized in the `testUpdater`/`testRepository`
  helpers so tests do not hand-roll it.
- **A quest earned while the WAL is being written and the process dies before Room commits is not
  delivered.** Correct: nothing was persisted, so nothing was earned. Recovery resolves it as a
  no-op, exactly as for any other transition.
- **`pendingQuestDates` grows unboundedly if a consumer never acknowledges.** One ISO date per
  unacknowledged day, and the Home surface acknowledges on presentation, so the realistic bound is
  the number of days between app opens. Not worth capping, and capping would silently drop an owed
  delivery.
