## Context

See `proposal.md` — Why. Four findings on the on-device derivation path, three of them
`severity/high` and all four producing damage that cannot be repaired from any external
source.

The structural facts that constrain every decision below:

```
                SteamSyncCoordinator — one process-wide Mutex
                ┌────────────────────────────────────────────┐
 SteamSyncWorker ┤  :207  whole run held                     │
 Reconciliation  ┤  :44   whole run held                     │──▶ sessions +
 PostPlaySync    ┤  :103 :174 :243                           │    daily_progress
 AccountChange   ┤  :91                                      │        ▲
 DailyBackfill   ┤  :109                                     │        │
                └─────────────────────────────────────────────┘       │
                                                                      │
 PresenceService ─▶ LiveStatusRepository :186 :220                     │
                    └─▶ PresenceSessionRecorder :69 :87 ───────────────┘
                        NO LOCK — this is #116
```

- The presence path never takes the coordinator. Adding it would be the smallest diff and is
  rejected in Decision 1.
- `Session.kt:28`'s `(appId, startAt, endAt)` index is **deliberately non-unique**, and the
  KDoc explains why: it backs the backup/restore merge engine's natural-key lookup, and "a
  stray real-world collision must never crash a sync or import, only cost that lookup a
  linear scan". Any uniqueness added here must not touch that key.
- `PresenceSessionDeriver` already handles non-monotonic clock movement; `SessionDiffer` does
  not. The fix is to bring one path up to the other, not to invent an approach.
- `WriteIntegrityDaoTest.kt:570` already asserts a concurrency property *without* the
  coordinator, and says so in a comment. That is the established test shape here.

## Goals / Non-Goals

**Goals:**

- One open session per game, guaranteed without relying on the process-wide mutex.
- No stored session with `endAt < startAt`, from either emission path.
- XP totals that cannot wrap for any accepted configuration, and a configuration validator
  that refuses input the engine cannot use.
- Removal recomputes that reseed rather than emit.

**Non-Goals:**

- Changing the scope of `SteamSyncCoordinator`. That is
  `auditfix-background-work-contracts`, and it is sequenced *after* this change for the
  reason in the proposal.
- Merging the two session derivation mechanisms. `CLAUDE.md` is explicit that two independent
  session detectors are the problem; the answer is one write boundary, not one detector.
- Splitting `PlayerProfile` into per-domain tables.
- Hide/unhide provenance. `add-hidden-games` owns that and inherits this change's pattern.

## Decisions

### Decision 1: Enforce single-open-session at the write boundary, not by extending the lock

**Alternatives considered:**

| Approach | Verdict |
|---|---|
| Have the presence path take `SteamSyncCoordinator` | **Rejected** |
| Unique index on `(appId, startAt, endAt)` | **Rejected** |
| Partial unique index on `appId WHERE open = 1`, plus a conflict strategy at the write boundary | **Chosen** |

**Why not the lock.** It is the smallest diff and it looks like the obvious fix, which is
what makes it worth arguing against explicitly. Three reasons:

1. It is the wrong lifetime. The presence poll runs every ~30 seconds from a foreground
   service; the workers hold that mutex for whole library-scale runs. Making a 30-second poll
   queue behind a multi-minute reconciliation either stalls live status or forces a
   `tryLock`-and-drop, which silently discards observations — and a dropped presence
   observation is a lost session boundary for a Family Shared game, since presence is that
   game's *only* session input.
2. It would be immediately undone. `auditfix-background-work-contracts` narrows this mutex
   precisely because holding it across whole runs is #99. Building a new correctness
   dependency on its current breadth means that change has to remove one.
3. The coordinator's own KDoc already rejects this reasoning: "Database commits still re-read
   their baselines, because WorkManager may run work in another process in a future build and
   tests must not need this lock to prove that concurrent observations cannot double-count."
   That is the house style — the lock is an optimization, the database is the guarantee — and
   the spec scenario "Correctness does not rest on a process lock" encodes it.

**Why not the plain unique index.** It would collide head-on with a documented deliberate
choice. `Session.kt`'s natural key is non-unique *on purpose* so that an unlucky collision
degrades a lookup instead of failing an import. Making it unique would trade #116 for import
failures on data that is already in the wild.

**The chosen shape.** Uniqueness on the *open* rows only — one open session per `appId` — so
the natural key is untouched and closed sessions stay as tolerant as they are today. The
write boundary then resolves a conflict by extending the existing open session rather than
inserting, which is what the second observation actually meant. Room supports a partial
index; if the target Room version's support proves awkward, the fallback is an
`INSERT … WHERE NOT EXISTS`-style guarded insert in `SessionDao` — same guarantee, same
place, expressed in SQL rather than in the schema. Task 2.1 settles which, and either
satisfies the spec.

The guard lives in `SessionActionWriter` because its KDoc already claims the role: "The one
path session actions take into storage, whichever mechanism produced them." Today that claim
is true for the *route* and false for the *guarantee*. This closes the gap.

### Decision 2: Clamp the inverted interval, and record it

For a backwards clock, `SessionDiffer` can refuse the action or clamp the boundary. Chosen:
**clamp to the existing boundary, and record that it happened.**

Refusing loses the playtime delta's session attribution entirely — the minutes are credited
(they come from Steam) but no session reflects them, which produces the *other* kind of
unreconcilable history. Clamping `endAt` to at least `startAt` keeps the interval valid and
the session present, at the cost of a session that under-reports its span. That is the right
trade: a slightly short interval is wrong in a bounded, explicable way; an inverted one is
impossible and poisons every consumer that subtracts the two.

Recording it matters because a clock rollback is a real-world event with a cause — a manual
clock change, a network time correction, a timezone-adjacent bug — and a silent clamp gives
a later investigation nothing to work from. `app-diagnostics` is the natural home; the spec
requires the refusal or clamp be "recorded rather than discarded silently" without naming a
mechanism.

Both emission sites are covered. The audit named `Extend`, but `Open(startAt =
previousPollAt, endAt = now)` derives both ends from clock readings and inverts under the
same rollback.

### Decision 3: Widen the accumulation *and* add the ceiling

The audit's finding admits two fixes, and each alone is insufficient:

- **Ceiling only** (`RuleField.maximum`): cheap, no migration. But it does nothing for the
  cross-game `games.sumOf { … }` or `achievementXp` overflow, which need no absurd setting —
  only a large enough library. It treats the trigger the audit happened to find rather than
  the class.
- **Widening only**: fixes the accumulation, but leaves `xpPerMinute = 2147483647` an
  accepted configuration that produces meaningless numbers. "Valid" should mean usable.

So both. Accumulate in `Long` inside the engine and derive levels from the widened total;
give `RuleField` a ceiling low enough that no accepted configuration can approach the
widened bound even across a maximal library, chosen with headroom rather than at the
arithmetic limit.

**The migration.** `XpState.totalXp` and `PlayerProfile.totalXp` are both `Int`. Widening
the persisted column is a schema migration, and per the archived
`auditfix-sync-write-integrity` reasoning it must not land before the migration chain test
exists — which is why `auditfix-spec-truth` is a hard prerequisite and not a preference.

The migration itself is a widening with no reinterpretation, so it is data-preserving in the
plain sense. The interesting case is a device whose stored `totalXp` is `0` *because* of this
bug: the migration must not try to be clever and guess a real value. It widens what is
stored; the next recompute produces the correct total. Task 5.5 handles the consequence — a
correction from `0` to a real total is a large upward move in derived state, and it must
reseed the baseline, not fire a cascade of level-up celebrations for progress the player
made long ago.

### Decision 4: A dedicated removal source, not a generic non-earned catch-all

`RecomputeSource` today is `SYNC | RULE_CHANGE | BACKFILL | RESTORE` — each naming *what
happened*, not merely whether it was earned. A generic `NON_EARNED` would break that pattern
and lose the attribution that makes the enum useful for diagnostics.

`add-hidden-games` will add its own for hide/unhide. Both are administrative, both are
non-earned, and they are still different events — a reader tracing a baseline reseed should
be able to tell a removal from a hide. Naming this one specifically leaves that change a
pattern to follow rather than a catch-all to widen.

`ProgressEventDetector.kt:40+` keys on earned provenance rather than enumerating non-earned
ones, so adding a source is additive there — the new source is non-event-producing by
construction. Task 4.3 verifies that rather than assuming it.

## Risks / Trade-offs

**The `totalXp` migration is the highest-risk item in the audit** → It lands only after
`auditfix-spec-truth` provides the v13-to-current chain test (task 1.1 gates on this), the
migration is a pure widening with no value reinterpretation, and task 5.2 asserts a populated
pre-migration profile survives with its total intact.

**A partial unique index may be awkward on the pinned Room version** → Decision 1 names the
guarded-insert fallback, and the spec is written as a behavioural guarantee rather than a
schema mandate, so either implementation satisfies it. Task 2.1 decides before 2.2 builds.

**Clamping under-reports a session's span** → Accepted and argued in Decision 2. Bounded and
explicable beats impossible. The clamp is recorded so it is diagnosable rather than invisible.

**Correcting a wrapped `totalXp = 0` looks like an enormous sudden gain** → Task 5.5 requires
the corrective recompute to reseed the baseline rather than emit. Getting this wrong would
turn a bug fix into a burst of unearned celebrations, which is the exact failure #104 is
about — so this change must not commit it while fixing it.

**Extending `WriteIntegrityDaoTest` may be flaky if it drives real concurrency** → Follow the
existing case's shape at `:570`, which already establishes how this codebase tests the
read-derive-write boundary without depending on the coordinator.

## Migration Plan

1. `auditfix-spec-truth` must be merged (chain test present, `live-status` text honest).
2. Session guard and interval clamp first — no schema change, independently shippable, and
   they are the two `severity/high` data-corruption fixes.
3. `RuleField` ceiling and engine widening, then the `PlayerProfile.totalXp` migration with
   its populated-fixture test.
4. Removal provenance last — smallest, and it touches files nothing else here touches.

Rollback: steps 2 and 4 are code-only. Step 3's migration is a widening, so a downgrade would
need a narrowing migration; do not ship step 3 behind a flag that could leave a device
oscillating between widths.

## Open Questions

None blocking. Decision 1's index-versus-guarded-insert choice is deliberately left to task
2.1 because both satisfy the spec and the answer depends on the pinned Room version, not on
anything the specs or task breakdown would change.
