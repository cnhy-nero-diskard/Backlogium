## Context

`GamificationUpdater.persist()` today does, in order: read the previous `PlayerProfile` and marks
(Room + DataStore reads), write the changed `DailyProgress` rows and the new `PlayerProfile` (Room
writes), run `ProgressEventDetector.detect()` against the *pre-write* snapshots, then write the
resulting `ProgressMarks` (one DataStore write). The Room writes and the marks write are two
independent commits to two different storage engines with no transaction spanning them.

`ProgressEventRepository.pendingEvents` re-derives events on every tick by comparing the live
`ProgressMarks` against the live `PlayerProfile`/`DailyProgress`. For `LevelUp` and
`StreakMilestone` this comparison is a monotonic-value-vs-mark check, so it is idempotent no matter
when it runs. `StreakBroken` is not: its only durable signal, `marks.pendingStreakBreak`, is
produced by comparing a *transient* `previous` snapshot (captured fresh at the top of each
`persist()` call, never stored) against the just-written `current` profile. Once Room's profile
row reflects `currentStreak == 0`, `previous.currentStreak > 0` can never be observed again — the
transition is gone the instant the crash outlives it. `QuestMet`'s reconstruction has a related but
distinct defect: the repository asks "is today's row met and unacknowledged," not "is any
unacknowledged day met," so a day that rolls over before the app reopens is never asked about
again.

`ProgressMarksStore` (`read()`/`write()`) and its two callers (`persist()`'s mark write,
`acknowledge()`) both do snapshot read → compute → snapshot write, each unaware of the other. Two
such cycles interleaving lose whichever write finishes first.

## Goals / Non-Goals

**Goals:**
- A crash at any point in `persist()` — before, during, or after the Room writes — must not cause a
  later read to report an event that was never earned, and must not cause a later read to miss an
  event (specifically `StreakBroken`) that was.
- An earned `QuestMet` remains deliverable regardless of how many calendar days pass before a
  consumer asks.
- Concurrent `acknowledge()` and mark-producing writes resolve deterministically to "both
  intentions applied," never "one clobbers the other."
- Home's streak-milestone celebration is driven by the same durable pending-event/acknowledge-on-
  present pattern as the streak-broken overlay.
- No Room migration. No change to `:gamification`, to `compute()`'s no-side-effect guarantee, to
  the required-provenance shape of `persist`, or to the documented event priority order.

**Non-Goals:**
- Making the per-row `dailyProgressDao.upsert()` loop transactional. It has no bearing on the
  event-durability defects being fixed here; a future change can address it independently if it
  ever needs to.
- Cross-store (Room+DataStore) atomicity in the general sense. This design achieves the specific
  guarantee needed (no phantom event, no lost event) via a write-ahead record and idempotent
  recovery, not via a literal joint transaction, which the two engines don't support.
- Migrating the `LevelUp` Home trigger. It has the same non-durable Compose-state shape as the
  streak-milestone trigger being fixed here, but it is not in scope for this change.

## Decisions

### 1. A durable pending-transition record, written before the Room write, resolved by a shared recovery routine

Add to `ProgressMarks`:

```kotlin
data class PendingTransition(
    val source: RecomputeSource,
    val previousLevel: Int,
    val previousStreak: Int,
    val previousTodayQuestMet: Boolean,
    val evaluationDate: LocalDate,
)
```

`persist()` becomes:

1. Resolve any leftover `pendingTransition` from a prior call (see step 4) before doing anything
   else — this makes `persist()` self-healing even if nothing else ever calls the recovery routine.
2. Read the previous profile/today's quest flag from Room (as today), building `previousState`.
3. Atomically write `marksStore.update { it.copy(pendingTransition = PendingTransition(source,
   previousState.level, previousState.currentStreak, previousState.todayQuestMet, today)) }` —
   this is the write-ahead step, and it is the *only* durable record of `previousState` once the
   Room write below lands.
4. Perform the Room writes (`changedDays`, profile upsert) exactly as today.
5. Atomically write `marksStore.update { marks -> ProgressEventDetector.detect(marks, previousState,
   currentState, source, today).marks.copy(pendingTransition = null) }`.

The recovery routine (`resolvePendingTransition`, shared by `persist()` step 1 and by
`ProgressEventRepository`):

1. Read marks; if `pendingTransition == null`, return immediately (cheap common case).
2. Read the current profile and the `evaluationDate` row from Room — this is *durable truth as of
   now*, whatever state the earlier crash left it in.
3. Atomically `marksStore.update { marks -> if (marks.pendingTransition == null) marks else
   ProgressEventDetector.detect(marks, recordedPreviousState, currentState, recordedSource,
   recordedEvaluationDate).marks.copy(pendingTransition = null) }`.

Because `detect()` is a pure function of its four inputs, replaying it after a crash produces
exactly what the interrupted `persist()` call would have produced, using the one piece of
information that stops being recoverable the moment Room's write lands: `previousState`. If the
crash happened before the Room write even started, `currentState == previousState` and `detect()`
correctly emits nothing. This is why the record must be written *before* the Room write, not
after — writing it after would reopen the exact window being closed. Recovery could also be
invoked opportunistically by `ProgressEventRepository` as an optimization for the "never opens the
app again mid-`persist()`" case (see Decision 2's actual placement).

**Alternative considered:** reverse the existing write order (marks first, Room second). Rejected
per the proposal — this only relocates the crash window from "Room ahead of marks" to "marks ahead
of Room," which for non-earned sources would let the marks reseed to values the profile never
reached, and for `SYNC` would let a `StreakBroken` fire before the streak actually reads as broken
in Room. The write-ahead record is the mechanism that avoids needing to pick a lesser ordering.

**Alternative considered:** a Room-side "commit sequence" column compared against a DataStore
counter. Rejected — this requires a Room migration and still needs the same previous-state capture
to resolve `StreakBroken`; the write-ahead record already carries the previous state directly, so a
sequence number would add a moving part without removing the need for this one.

### 2. Recovery runs at the start of `persist()` and at the start of every `pendingEvents` collection

`GamificationUpdater.persist()` calling recovery first protects the case where recompute happens
again. `ProgressEventRepository.pendingEvents` also calling it — wrapped as `flow { resolve...();
emitAll(combine(...)) }` — protects the case where the interrupted source never recomputes again
before the player opens the app (e.g. a `RULE_CHANGE` crashes mid-persist and the player's next
action is just opening Home). Both call the same top-level `resolvePendingTransition()` function so
the recovery logic exists once. The fast-path null check keeps this cheap on every normal
collection/persist.

### 3. `QuestMet` reconstruction scans for the earliest unacknowledged date, not "today"

`ProgressEventRepository` currently asks "is `time.today()`'s row met and is it not equal to the
mark." This is changed to: find the earliest `DailyProgress` row with `questMet == true` and a
parsed date strictly after `marks.lastQuestCelebratedDate` (or any met date at all, if the mark is
null) — that row's date, if any, is the pending `QuestMet`. `lastQuestCelebratedDate` keeps its
existing meaning as the acknowledged high-water mark; only the *query* changes, not the mark's
shape. This is why the fix is a repository-level change, not a `ProgressMarks` schema change: the
underlying data (`DailyProgress.questMet` per date) was always sufficient, and `time.today()` was
simply the wrong filter.

Multiple pending days resolve one at a time, oldest first: acknowledging day N reveals day N+1 on
the next `combine` tick if it's also unacknowledged-and-met, rather than either day silently
suppressing the other. `ProgressEventDetector.detect()`'s own `QuestMet` emission (used only for
the immediate evaluation day at persist time) is unchanged — the historical scan lives solely in
the repository's re-derivation, which is the only place "how many days have passed" is a concern.

**Alternative considered:** add a `pendingQuestDates: Set<LocalDate>` (or single
`pendingQuestDate`) field to `ProgressMarks`, mirroring `pendingStreakBreak`. Rejected — quest-met
is a genuinely idempotent, monotonic-vs-durable-source comparison (unlike the edge-triggered streak
break), so it doesn't need a separate producer-side pending marker; scanning `DailyProgress` is both
simpler and automatically correct for however many days have accumulated.

### 4. `ProgressMarksStore.update(transform)` — one atomic edit, live parameter only

```kotlin
interface ProgressMarksStore {
    val marks: Flow<ProgressMarks>
    suspend fun read(): ProgressMarks
    suspend fun write(marks: ProgressMarks)
    suspend fun update(transform: (ProgressMarks) -> ProgressMarks): ProgressMarks
}
```

Production (`SettingsDataStore`) implements it as one `context.dataStore.edit { prefs -> val next =
transform(decode(prefs)); encode(prefs, next) }` — DataStore's `edit {}` already serializes
concurrent calls against the same file, applying each to the latest on-disk state, so this is
sufficient without any additional locking. `InMemoryProgressMarksStore` implements it with
`state.update { transform(it) }` (an atomic `MutableStateFlow` update).

The correctness this buys depends entirely on every caller passing `transform` as a function of its
*live* parameter, never closing over an outer `read()` result. `persist()`'s finalize step
(`ProgressEventDetector.detect(marks, previousState, currentState, source, today)`, called *inside*
the `update {}` lambda using the block's `marks` argument) and `acknowledge()`'s `when` branch
(also computed from the block's argument) both follow this rule — `detect()` and the `acknowledge`
branches are cheap and pure, so recomputing them on every `edit {}` retry is free. This is what
prevents the resurrection scenario: whichever of a racing `acknowledge()`/finalize pair commits
second sees the first one's result as its starting point, not a stale pre-race snapshot.

`read()`/`write()` stay on the interface for the seed/read-only cases that don't need the
transform's atomicity (e.g. `resolvePendingTransition`'s initial cheap null-check read).

**Alternative considered:** a `Mutex` guarding `read()` + `write()` pairs at the call sites instead
of a store-level `update`. Rejected — a mutex inside `GamificationUpdater`/`ProgressEventRepository`
wouldn't cover both classes without sharing the same lock instance across two different injected
singletons, which is more fragile than pushing the atomicity into the store itself, and it
wouldn't survive process death the way DataStore's file-level `edit {}` naturally does.

### 5. Home's streak-milestone celebration follows the `StreakBroken` pattern exactly

`HomeUiState` gains `pendingStreakMilestone: ProgressEvent.StreakMilestone?`, populated the same
way `pendingStreakBreak` is: `pendingEvents.filterIsInstance<ProgressEvent.StreakMilestone>()
.firstOrNull()`. `HomeScreen`'s `lastStreak`/`playStreakMilestone`/`isStreakMilestone(...)`
`LaunchedEffect` is removed; the `CelebrationAnimation` call it drove is instead triggered by
`state.pendingStreakMilestone != null`, and its `onFinished` calls
`viewModel.acknowledgeProgressEvent(milestone)` — acknowledging only once the Lottie animation has
actually completed, matching the existing "acknowledge after presenting" rule. This keeps the
trigger inside `HomeScreen`/`InnerHomeContent` (where the animation itself lives) rather than
moving it into `HomeRoute` as a separate overlay like `StreakBroken`, since a milestone celebration
is an in-place accent on the Streak card, not a standalone card.

## Risks / Trade-offs

- **`persist()` now does two DataStore writes instead of one**, adding latency to every recompute.
  → Accepted: DataStore writes are cheap relative to the Room writes already happening in the same
  call, and the alternative (no write-ahead record) is the exact defect being fixed.
- **A crash between the write-ahead record and the Room write leaves a `pendingTransition` that
  recovery resolves as a no-op** (since `currentState == previousState`), which is a wasted
  `detect()` call but not an incorrect one. → Accepted, harmless.
- **`resolvePendingTransition` adds a Room read to every `pendingEvents` collection start.** →
  Mitigated by the null-check fast path: the extra reads only happen when a `pendingTransition`
  actually exists, which is the crash case this exists to handle.
- **This is the second change to touch `ProgressMarks`'s shape** (after the original
  `pendingStreakBreak` multiplexing). → Accepted; `PendingTransition` gets its own preference keys
  rather than being multiplexed onto an existing string, so it doesn't compound the existing
  encoding trick.
