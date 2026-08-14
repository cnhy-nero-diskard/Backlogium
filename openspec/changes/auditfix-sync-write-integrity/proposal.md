## Why

`SteamSyncWorker.persistPoll` reads the state it needs at the top, performs network I/O
in the middle, and writes six different tables at the bottom — with no transaction, no
mutual exclusion against a second copy of itself, and whole-row upserts that carry
stale copies of columns the sync does not own. Six audit findings land here, and they
are not six bugs so much as one structural gap seen from six angles.

The concrete confirmed shapes:

- **Two copies can run at once.** `SyncScheduler.kt:126` and `:140` register the
  periodic and one-shot polls under *different* unique work names, so "Sync now" during
  a scheduled poll runs both concurrently. Both read `profile.lastSyncAt` (`:143`) and
  `game.lastPlaytime` (`:146`), both observe the same Steam increase, both insert
  sessions, and both add the same delta to today's `DailyProgress` via a
  read-add-write (`:200-206`). One real play interval becomes two sessions and twice
  the minutes.
- **A crash mid-write loses playtime permanently.** `gameDao.upsertAll` at `:189`
  advances `lastPlaytime` — the diff baseline. `dailyProgressDao.upsert` at `:201`
  credits the minutes. Process death between them means the next poll sees no delta
  and those minutes are unreconstructable, because the baseline already moved past them.
- **The sync writes back stale app-owned columns.** `:172-188` rebuilds each `Game`
  from the DTO plus an `existing` row read at `:146`, re-asserting `isGoal`,
  `targetMinutes`, and `backfillMinutes` from that earlier read. A focus toggle or a
  history import completing in between is silently reverted. The existing comment at
  `:184-186` shows the hazard was understood; preserving values across a
  read-modify-write is the wrong remedy for it.
- **`PlayerProfile` is a lost-update hotspot.** Read at `:141`, written whole at
  `:214`, with session writes, game upserts, and daily-progress writes in between.
  Sync status, Steam identity, gamification aggregates, and history-import state all
  live in that one row with four independent writers.
- **Gamification can persist under superseded rules.** `config` is sampled at `:139`
  and used at `:249` — after `achievementRepository.syncLibraryGames`, which is
  library-scale network I/O. A rule change committed during that window is correctly
  applied by settings and then overwritten by this run using the old snapshot.
- **Achievement refreshes can apply out of order.** `ReconciliationWorker` and the
  in-sync achievement refresh can both merge the same game's achievements from
  separately-read state, so a slower older refresh can land after a newer one.

## What Changes

- **One sync at a time, enforced in the database.** The commit re-reads the baselines it
  diffs from and recomputes the delta against them, so a second poll committing after the
  first observes the advanced baseline and writes nothing. A process-wide lock additionally
  stops a redundant poll from spending Steam requests, but correctness does not rest on it.
  **The two workers keep their separate unique work names** — merging them is unworkable
  for the reason `SyncScheduler.kt:173-177` already documents: unique-work names are a
  single namespace, a periodic request sits `ENQUEUED` almost permanently, and `KEEP` would
  then drop nearly every manual sync, including while idle.
- **Persistence of raw data becomes one atomic step.** Sessions, game baselines, daily
  progress, and profile fields commit together or not at all. Advancing a diff baseline and
  crediting the minutes that advance represents stop being separately-failable operations.
  Derived gamification values persist immediately afterwards through the existing
  write-ahead protocol, which cannot join a Room transaction — see below.
- **Steam-owned and app-owned columns get separate write paths.** The sync updates only
  the columns Steam is the authority for, via targeted queries. `isGoal`,
  `targetMinutes`, and `backfillMinutes` are never written by the sync at all, so there
  is nothing stale to write back.
- **`PlayerProfile` writes become field-scoped.** Each writer updates only the columns
  it owns. Whole-row upsert stops being the mechanism by which unrelated domains
  overwrite each other.
- **Rule configuration becomes versioned and is compared at commit.** `RuleConfig` lives in
  DataStore and derived values live in Room, so no transaction can span them and "read the
  config inside the commit" is not implementable. Instead the configuration carries a
  monotonic version, the version is read with the config, re-checked before derived values
  are written, and stamped alongside them. A superseded write is refused and recomputed
  rather than silently landing — and afterwards it is possible to *tell* which rules
  produced a stored value, which today nothing records.
- **Achievement rows gain defined removal semantics.** `AchievementDao` currently has
  no delete path at all, so a row Steam stops returning persists forever and keeps
  counting toward totals and XP. Reconciliation gets an explicit rule for what to do.
- **The N+1 in `GamificationUpdater` is removed.** `:109` calls
  `hltbDataDao.getByAppId()` inside a `map` over the library, eight lines after a bulk
  `gameDao.getAll()` at `:101`. A bulk read plus an in-memory map replaces hundreds of
  queries per sync, settings preview, rule application, restore, and history import.

## Capabilities

### Modified Capabilities

- `steam-sync`: add requirements that a poll's persistence is atomic, that at most one
  poll runs at a time, that the sync writes only Steam-owned fields, and that the
  rule configuration used to derive a value is the one committed with it.
- `steam-achievements`: add serialization for concurrent refreshes of the same game,
  and define removal semantics for achievements Steam no longer returns.

## Impact

| Path | Change |
|---|---|
| `work/SyncScheduler.kt` | single unique work name for both poll entry points |
| `work/SteamSyncWorker.kt` | persistence extracted into one transactional unit |
| `work/ReconciliationWorker.kt` | serialized against in-sync achievement refresh |
| `data/local/dao/GameDao.kt` | targeted Steam-field update query |
| `data/local/dao/PlayerProfileDao.kt` | field-scoped update queries per owner |
| `data/local/dao/DailyProgressDao.kt` | atomic additive update |
| `data/local/dao/AchievementDao.kt` | removal path |
| `domain/GamificationUpdater.kt` | bulk HLTB read; participates in caller's transaction |
| `data/repo/AchievementRepository.kt` | per-game merge serialization |

**BREAKING (behavioural, not schema)**: "Sync now" tapped while a scheduled poll is
running will no longer start a second poll. It will either join or be dropped — design
picks which. This is the intended fix, but it is a user-visible change to a button's
responsiveness and needs the UI to say something honest.

**Depends on `auditfix-verification-coverage`.** This change alters DAO surfaces and may
add a migration. It should not land before migration tests exist, for the reason stated
in that proposal: this app's data cannot be re-derived.

**Interacts with `auditfix-day-attribution`.** That change alters *which day* a delta is
credited to; this one alters *how* the credit is written. Landing this first gives that
change a transaction to work inside.

**Not addressed here**: whether the sync should attribute deltas to a different day
(`auditfix-day-attribution`), and whether the profile row should be split into separate
tables. Field-scoped writes make the single-table design safe; splitting it is a larger
refactor with no finding demanding it.
