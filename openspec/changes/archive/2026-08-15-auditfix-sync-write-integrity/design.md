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
             read (config, version); diff, session actions, day deltas,
             provisional gamification result
        │
        ▼
  PHASE 3  commit (one Room transaction, no network, NonCancellable)
             re-read baselines, recompute the delta against them,
             then: sessions + game baselines + daily progress + profile fields
        │
        ▼
  PHASE 4  derived state (outside the transaction, existing WAL protocol)
             GamificationUpdater.persistWithinProtocol, config version compared
             and stamped; recoverable if interrupted
```

**Phase 4 is separate and must stay separate.** `GamificationUpdater.persistWithinProtocol`
suspends on `progressMarksStore` (DataStore) and owns a non-reentrant coordinator; it cannot
run inside a Room transaction. See Decision 4a. A crash between phase 3 and phase 4 leaves
raw data committed and derived values stale — which the existing WAL is built to detect and
resolve on the next entry, and which is strictly better than today's failure mode of an
advanced baseline with uncredited minutes.

The atomicity requirement therefore applies to phase 3: **the baseline advance and the
progress crediting it represents are what must not be separable.** Derived values are
regenerable from committed raw data; raw data is not regenerable from anything.

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

**Phase 3 also re-reads what phase 2 diffed from** (Decision 2, layer 1), so phase 2's
result is provisional. Two computations of the same diff is a small cost for making
concurrency a database property rather than a scheduling assumption.

## Decision 2: Serialization — keep separate work names; serialize in the database

**A shared unique work name is wrong and this file already says so.** `SyncScheduler.kt:173-177`
documents the exact hazard for `ReconciliationWorker`:

> WorkManager's unique-work names are a single namespace regardless of one-time vs periodic,
> so sharing a name with [ensurePeriodicReconciliation]'s always-enqueued periodic work would
> let `KEEP` silently drop this request whenever the periodic work is already sitting
> enqueued — which is most of the time.

A `PeriodicWorkRequest` sits `ENQUEUED` between runs, which is nearly always. Merging
`UNIQUE_PERIODIC_NAME` and `ONE_TIME_NAME` under `KEEP` would therefore drop essentially
every manual sync — including when nothing is running, which is precisely the case the
requirement says must work. The separate names are not an oversight; they are load-bearing.

**Chosen: two layers, with correctness in the database.**

```
  LAYER 1 (correctness)  — the commit transaction re-reads baselines
      the diff's inputs (lastPlaytime, lastSyncAt, open sessions) are re-read
      INSIDE the transaction and the delta recomputed against them. A second
      poll committing after the first sees the advanced baseline, computes a
      zero delta, and writes nothing. Guarantees no double-count regardless
      of process topology, scheduling, or how the two workers were enqueued.

  LAYER 2 (efficiency + UX) — a process-wide Mutex
      a @Singleton Mutex with tryLock() around the whole poll. A second poll
      that cannot acquire it returns immediately without spending Steam API
      requests. Not the correctness mechanism — an optimization.
```

Layer 1 is the important one. Recomputing the diff from state read inside the transaction
means concurrency is resolved by the database's own serialization rather than by an
assumption about how work was scheduled. It also composes with the atomicity requirement:
the same transaction that must not be split is the one that must observe fresh baselines.

**This changes phase 2's status.** The compute phase becomes a *provisional* computation
whose result is recomputed — or at minimum revalidated — against state read inside the
commit. Phase 2 exists to keep network I/O out of the transaction, not to make the diff
final. Implementation must not cache the phase-2 delta and write it blindly.

**Layer 2's caveat, stated rather than assumed**: a `Mutex` in a Hilt `@Singleton` is
process-scoped. Backlogium is single-process, so this holds today. If a future change moves
WorkManager to a separate process, layer 2 silently stops working — and layer 1 still
guarantees correctness, which is the reason for putting correctness there.

**Rejected: `APPEND_OR_REPLACE` on a shared name.** WorkManager does not support appending
to periodic work, and a queued second poll has nothing to do — the first already observed
the current Steam state.

**Rejected: a uniqueness constraint on `Session` rows.** The audit suggests this. It treats
the symptom: two concurrent polls would still both add minutes to `DailyProgress` and both
advance `lastPlaytime`, neither of which a session constraint protects.

**UI consequence.** With `tryLock`, a manual tap during a running poll is absorbed.
`syncInProgress` already exists and must reflect it — the precedent at `SyncScheduler.kt:258`
("a selection tapped *during* a sweep is dropped with no error") is acceptable for a
background sweep and not for a button the user just pressed.

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

The current writer map is:

| Writer | Owned fields |
|---|---|
| `SteamSyncWorker.persistPoll` | `steamId`, `steamLevel`, `lastSyncAt`, `lastSyncError`, `personaName`, `avatarUrl` |
| `SteamSyncWorker.recordError` | `lastSyncError` |
| `LiveStatusRepository.refreshStoredIdentity` | `personaName`, `avatarUrl` |
| `GamificationUpdater` | `totalXp`, `level`, `currentStreak`, `longestStreak` |
| `PlaytimeBackfillUseCase` | `playtimeBackfilled` |
| `BackupMergeEngine` restore | `playtimeBackfilled` (one-way OR); aggregate fields are owned by `GamificationUpdater` |

`ProfileRepository`, `BackupExportMapper`, `ProgressEventRepository`, and recovery
helpers read the profile but do not currently write it directly. This map is the
source of truth for the field-scoped DAO methods below.

`DailyProgress` needs an additive SQL update (`SET minutesPlayed = minutesPlayed + :d`)
rather than a read-add-write, so the addition is atomic in the database even inside a
transaction.

## Decision 4: Rule configuration — versioned, compared at commit

An earlier draft of this design said to compute gamification in phase 2 and sample
`config` in phase 3. That is incoherent: the computation consumes the configuration, so
the configuration cannot be read after it. Correcting the ordering exposes the real
problem, which is that no ordering solves this.

**`RuleConfig` lives in `SettingsDataStore`; derived values live in Room. The two share no
transaction, so "read the config inside the commit" is not implementable.** Moving the read
later only shrinks the window; it never closes it, and a shrinking window is the kind of fix
that looks correct until it fails.

**Chosen: version the configuration and compare at commit.**

```
  phase 2 (compute)          read (config, version V) together
                             derive XP / quests / streaks from it
        │
  phase 3 (commit)           re-read the current version
        │
        ├── still V   →  commit derived values, stamping V alongside them
        └── now V+1   →  abort the derived-value portion of the commit
                         (raw playtime data still commits; a recompute follows)
```

`SettingsDataStore` gains a monotonic version incremented on every `RuleConfig` write, read
atomically with the config itself. Room gains a column recording which version produced the
stored derived values.

This is a compare-and-set across two stores, not a transaction across them. It does not make
the write atomic; it makes a stale write **detectable and refusable**, which is the property
actually needed. The failure mode being fixed is silent overwrite by superseded rules — a
refused commit followed by a recompute is a correct outcome.

**The raw/derived split matters.** A version mismatch must not discard the poll's observed
playtime — that data is unrecoverable and configuration-independent. Sessions, game
baselines, and daily progress commit regardless; only XP, quest results, and streaks are
withheld, and the follow-up recompute regenerates them from the committed raw data under the
current configuration.

**Stamping the version in Room is what makes this auditable.** Persisted rules and persisted
derived state can be *compared* afterwards, so a disagreement is a detectable defect rather
than an invisible one. That is a strictly stronger position than today, where nothing records
which rules produced a stored value.

**`UpdateRuleConfigUseCase` is the other writer** and must participate: it increments the
version and stamps its own recompute. Two writers remain, but the loser now loses visibly.

## Decision 4a: The cross-store pattern, and where it already exists

Decision 4 is one instance of a constraint that recurs across this whole audit-fix effort:
**Room and DataStore share no transaction.** `RuleConfig`, `ProgressMarks`, and credentials
all live outside Room while the state they relate to lives inside it.

The codebase already solved this once, and that solution is the model. `GamificationUpdater.
persistWithinProtocol` implements a write-ahead log: record the pending transition in
`progressMarksStore`, perform the Room write, finalize the marks, and resolve any dangling
record on the next entry. Its own comment states the reasoning — "the WAL exists to survive
process death". Its coordinator is explicitly **not reentrant**.

Two rules follow for every change in this effort:

1. **Never claim cross-store atomicity.** Specify crash-consistent ordering plus a recovery
   step, and enumerate the intermediate states as recoverable.
2. **Never nest an existing protocol inside a new Room transaction.** `persistWithinProtocol`
   suspends on DataStore and owns a non-reentrant coordinator; calling it inside
   `withTransaction` risks deadlock and defeats the WAL, whose entire purpose is to survive a
   crash *because* the two stores cannot commit together.

This is why `auditfix-backup-integrity` places the recompute after its merge transaction
rather than inside it, and why `auditfix-account-identity` specifies a resumable reset rather
than an atomic one.

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

- **Manual sync is never dropped**: request one while idle, assert it runs. This is the
  regression test for the rejected shared-work-name design.
- **No double-count**: two polls observing one increase, both reaching commit, produce one
  session and one credit — asserted with the mutex disabled, proving correctness comes from
  the transaction and not the lock.
- **Atomicity**: inject a failure between the game-baseline write and the daily-progress
  write; assert `lastPlaytime` did **not** advance. This is the regression test for the
  permanent-loss finding and it is the single most important test in this change.
- **Raw/derived boundary**: interrupt between the raw commit and the derived write; assert raw
  data survives and the existing protocol resolves the dangling state on the next entry.
- **Column ownership**: toggle `isGoal` on a row, run a poll built from a snapshot read
  before the toggle, assert `isGoal` survived.
- **Profile**: interleave a gamification write and a sync-status write, assert neither
  field is lost.
- **Stale config**: change rules between the compute read and the derived write; assert the
  derived write is refused, the raw data still commits, and a recompute follows. Separately,
  assert the stored version stamp matches the configuration that produced the stored values.
- **Daily progress**: two additive updates, assert the sum.

## What this change deliberately does not do

- Does not change which day a delta is credited to — `auditfix-day-attribution`.
- Does not split `PlayerProfile` into per-domain tables. Field-scoped writes make the
  single row safe; splitting is a large refactor no finding requires.
- Does not add account scoping — `auditfix-account-identity`.
- Does not serialize `UpdateRuleConfigUseCase` against the sync. Noted in Decision 4 as
  a known remaining overlap whose consequences are now bounded.
