# Design

## Context

The sync path's shape today:

```
  read profile (:141) ─┐
  read games   (:146) ─┤ state snapshot
  read sessions(:147) ─┘
        │
        ▼
  diff → session actions
        │
  ┌─────┴──────────────────────────────────────────────┐
  │ write sessions        (:169)                       │  each write independently
  │ write games  ← advances lastPlaytime (:189)        │  failable; a crash between
  │ write daily progress ← credits minutes (:201)      │  :189 and :201 loses data
  │ write profile ← advances lastSyncAt   (:214)       │  permanently
  │ ── NETWORK: achievement sync ──       (:225)       │  ← I/O inside the write phase
  │ recompute gamification, using :139's config (:249) │
  └────────────────────────────────────────────────────┘
```

Two things are wrong structurally, and everything else follows from them: **network I/O
sits inside the write phase**, and **there is no boundary around the write phase at
all**. Fixing the second requires fixing the first, because Room transactions must not
span network calls.

## Decision 1: Restructure into fetch → compute → commit

```
  PHASE 1  fetch (network, cancellable, no writes)
             owned games, presence summary, Steam level, achievement payloads
        │
        ▼
  PHASE 2  compute (pure, no I/O)
             diff, session actions, day deltas, gamification result
        │
        ▼
  PHASE 3  commit (one Room transaction, no network, NonCancellable)
             sessions + game baselines + daily progress + profile + gamification
```

Achievement fetching moves into phase 1. It is currently at `:225`, *after* the profile
write and *before* the recompute, which is why `config` goes stale and why the write
phase contains I/O at all.

**Consequence to accept**: phase 1 becomes longer before anything is written, so a
failure mid-fetch now discards more completed work than today. That is the correct
trade — today's partial-write behaviour is the finding. And "never discards last-good
data on failure" (the worker's own KDoc) is *better* served by writing nothing than by
writing half.

**The transaction must not be cancellable.** Phase 3 runs in `withContext(NonCancellable)`.
The existing `finally` block at `:123` already uses this pattern for the diagnostics
record, with a comment explaining exactly why — extend the same reasoning to the commit.

## Decision 2: Serialization — one unique work name, `KEEP`

**Chosen**: both entry points use one unique name. `syncNow()` keeps
`ExistingWorkPolicy.KEEP` semantics, so a manual tap during an in-flight poll is a
no-op rather than a queued second poll.

**Rejected: `APPEND_OR_REPLACE`.** A queued second poll immediately after the first has
nothing to do — the first poll already observed the current Steam state. It would double
the request budget for no new information.

**Rejected: an app-level `Mutex`.** WorkManager can run the two requests in separate
processes in principle, and a `Mutex` in a Hilt singleton would not span them. Unique
work names are the mechanism WorkManager provides for exactly this.

**Rejected: a uniqueness constraint on `Session` rows.** The audit suggests this as an
alternative. It treats the symptom: two concurrent polls would still both add minutes to
`DailyProgress` (a read-add-write at `:200-206`, which no session constraint protects)
and both advance `lastPlaytime`. Serialize the operation, don't deduplicate its output.

**The UI consequence is real and must be handled honestly.** `syncInProgress` already
exists in `SyncScheduler`, and `syncNow()` becoming a no-op during a poll is invisible
unless the button reflects it. The existing comment at `SyncScheduler.kt:258` shows this
pattern is already understood elsewhere in the file ("a selection tapped *during* a sweep
is dropped with no error") — that precedent is fine for a background sweep and not fine
for a button the user just pressed expecting a response.

## Decision 3: Column ownership — Steam writes Steam's columns only

The `Game` row has two classes of column with different authorities:

| Steam-owned | App-owned |
|---|---|
| `name`, `iconUrl` | `isGoal` |
| `playtimeForever`, `playtime2Weeks` | `targetMinutes` |
| `lastPlaytime`, `lastSyncedAt` | `backfillMinutes` |

**Chosen**: a targeted `@Query` updating only the left column set, plus an insert for
appIds not yet present. The sync never names an app-owned column, so staleness is
structurally impossible rather than defended against.

```sql
UPDATE games SET name = :name, iconUrl = :iconUrl,
                 playtimeForever = :playtimeForever, playtime2Weeks = :playtime2Weeks,
                 lastPlaytime = :lastPlaytime, lastSyncedAt = :lastSyncedAt
 WHERE appId = :appId
```

New games still need a full insert with app-owned defaults. Split the poll's game list
into known and unknown appIds inside the transaction — `INSERT OR IGNORE` followed by
the targeted update also works and is one fewer read.

**Rejected**: keeping the whole-row upsert and reading `existing` inside the
transaction. It would close the window, and it would leave the next person to touch this
code one forgotten field away from reintroducing the bug. The current code already
"preserves" `backfillMinutes` and `isGoal` deliberately (`:181-186`) and still has the
defect — evidence that preservation-by-copying does not survive maintenance.

Same reasoning for `PlayerProfile`: field-scoped update queries per owning domain
(sync status / Steam identity / gamification aggregates / history-import state) rather
than one `upsert(profile.copy(...))`. Note `recordError` at `:280-283` is a second
read-modify-write on the same row and needs the same treatment.

`DailyProgress` needs an additive SQL update (`SET minutesPlayed = minutesPlayed + :d`)
rather than a read-add-write, so the addition is atomic in the database even inside a
transaction.

## Decision 4: Rule configuration is read inside the commit

Sample `config` in phase 3, not phase 1. Since gamification is computed in phase 2, the
ordering becomes: read config → compute → commit, all without an intervening suspension
that another writer could use.

`UpdateRuleConfigUseCase` also recomputes. Two writers of the same derived state
therefore still exist; serializing them is out of scope here, but the invariant this
change establishes — *the config committed with a derived value is the config used to
derive it* — makes a late-landing sync's write correct-for-its-inputs rather than
silently wrong. A settings change immediately followed by a sync will produce the
settings result and then the sync result, both internally consistent. That is acceptable;
two disagreeing snapshots of the same instant is not.

## Decision 5: Achievement merge serialization and pruning

**Serialization**: the same unique-work-name approach. `ReconciliationWorker`'s
achievement refresh and the sync's in-line refresh must not both be in flight. Since the
sync now fetches achievements in phase 1 and merges in phase 3, giving reconciliation
the same unique name as the sync serializes both.

**Pruning** is the genuinely open question, and it is a *product* decision:

| Option | Behaviour | Risk |
|---|---|---|
| Never prune (today) | stale rows count forever | totals drift, unlocks that no longer exist keep XP |
| Prune on absence | row deleted when Steam omits it | one anomalous API response destroys history |
| Tombstone | mark absent, exclude from counts, keep the row | preserves the rarity snapshot; needs a column |

**Chosen: tombstone**, and only during full reconciliation — never during a normal sync
refresh. Reasoning consistent with the rest of this project: Steam's response is not
authoritative about the past, and `snapshotPercent` carries a first-unlock value that
`backup-restore` explicitly protects as unrecoverable. Deleting the row throws that away
on the strength of one HTTP response. A tombstone is reversible; a delete is not.

This needs a schema column and therefore a migration — another reason
`auditfix-verification-coverage` lands first.

## Decision 6: The N+1

`GamificationUpdater.kt:109` calls `hltbDataDao.getByAppId(appId)` per played game.
`hltbDataDao.getAll()` already exists, and `:101` already does a bulk `gameDao.getAll()`
immediately above. Replace with one bulk read and an `associateBy`, matching the pattern
the adjacent line already uses.

Trivially safe and included here only because it is in the function this change is
already restructuring — it would be churn as a standalone change.

## Testing strategy

Concurrency and atomicity are exactly the things unit tests on fresh databases miss,
which is why the tests matter more than usual:

- **Serialization**: enqueue both entry points, assert one execution.
- **Atomicity**: inject a failure between the game-baseline write and the daily-progress
  write; assert `lastPlaytime` did **not** advance. This is the regression test for the
  permanent-loss finding and it is the single most important test in this change.
- **Column ownership**: toggle `isGoal` on a row, run a poll built from a snapshot read
  before the toggle, assert `isGoal` survived.
- **Profile**: interleave a gamification write and a sync-status write, assert neither
  field is lost.
- **Stale config**: change rules between phase 1 and phase 3, assert the committed
  config matches the committed derived values.
- **Daily progress**: two additive updates, assert the sum.

## What this change deliberately does not do

- Does not change which day a delta is credited to — `auditfix-day-attribution`.
- Does not split `PlayerProfile` into per-domain tables. Field-scoped writes make the
  single row safe; splitting is a large refactor no finding requires.
- Does not add account scoping — `auditfix-account-identity`.
- Does not serialize `UpdateRuleConfigUseCase` against the sync. Noted in Decision 4 as
  a known remaining overlap whose consequences are now bounded.
