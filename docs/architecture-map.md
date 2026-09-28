# Backlogium architecture map

Status snapshot: 2026-08-21

This is a source-oriented map of the current repository. It describes the Android
client and the independent cloud presence writer as they exist on the current
branch; it is not a proposed replacement architecture.

```text
+-------------------------------------------------------------------------+
| Product boundary                                                        |
|                                                                         |
|  Backlogium = offline-first Android client + independent cloud writer   |
+-----------------------------------+-------------------------------------+
                                    |
                                    v
+-------------------------------------------------------------------------+
| Android client (:app)                                                   |
|                                                                         |
|  MainActivity -> BacklogiumApp -> BacklogiumAppRoot                     |
|                                      |                                  |
|                         +------------+-------------+                    |
|                         |                          |                    |
|                  top-level navigation       pushed routes              |
|                         |                          |                    |
|          Home | Library | History |       Onboarding / Setup                    |
|          Analytics | Settings             Game detail                  |
|                                               HLTB review               |
|                                               Collection                |
|                                               Diagnostics               |
+-------------------------------------------------------------------------+
```

## Runtime layers

```text
+----------------------+       +--------------------------+
| Compose UI           |       | App shell                |
| screens, components, | <---- | navigation, profile     |
| themes, UI state     |       | header, notification     |
+----------+-----------+       +------------+-------------+
           |                                 |
           v                                 v
+----------------------+       +--------------------------+
| ViewModels           | ----> | Repositories             |
| screen state,        |       | local-first data         |
| user actions         |       | and remote boundaries    |
+----------+-----------+       +------+-------------------+
           |                           |
           v                           v
+----------------------+       +--------------------------+
| Domain               |       | Background execution    |
| session differ, XP,  | <---- | WorkManager workers,    |
| pacing, collections, |       | foreground presence     |
| sorting, formatting  |       | service, schedulers     |
+----------------------+       +------+-------------------+
                                         |
                                         v
                              +--------------------------+
                              | Persistence + integrations|
                              | Room, DataStore, Steam,  |
                              | HLTB, Store genres       |
                              +--------------------------+
```

The intended dependency direction is UI -> ViewModel -> repository/domain,
with Room entities and remote DTOs kept below the repository boundary. The
developer-facing diagnostics surface deliberately reads diagnostic rows
directly so it can show what was persisted. `HomeViewModel` still has a known
collection-entity boundary breach documented in the root README.

## Local data and business logic

```text
+------------------------------ Android local state ----------------------+
|                                                                         |
|  Room database (schema version 41)                                     |
|    games                 sessions              daily_progress            |
|    player_profile       hltb_data              achievements              |
|    game_achievement_sync                         collections             |
|    collection_members   game_genre_cache                               |
|    cloud_historical_operations / intervals / boundaries / journals    |
|    sync_runs            request_breakdowns       presence_decisions     |
|                                                                         |
|  DataStore / Keystore                                                    |
|    encrypted Steam credentials     app settings and sync preferences   |
|                                                                         |
|  Standalone :gamification JVM module                                    |
|    XP and level calculations        achievement rarity standing        |
+-------------------------------------------------------------------------+
```

Repositories are the main bridge into this state:

```text
SteamSyncWorker / ViewModels / BackupRepository
                    |
                    v
        +---------------------------+
        | data.repo                 |
        | Game, Session, Profile,   |
        | Achievement, HLTB, Genre, |
        | Collection, Backup,       |
        | Settings, Live status     |
        +-------------+-------------+
                      |
          +-----------+------------+
          |                        |
          v                        v
   Room DAOs/entities       DataStore + Keystore
```

`BackupRepository` serializes a local JSON backup, merges it through
`BackupMergeEngine`, and uses `SnapshotStore` for optional rolling automatic
snapshots. A restore also queues a deferred achievement reconciliation pass.

## External integrations

```text
+-------------------+       +------------------------------+
| Steam Web API     | ----> | Retrofit / OkHttp             |
| library, profile, |       | SteamApi                     |
| level, playtime,  |       | SteamStoreApi                |
| achievements,     |       |                              |
| presence, players |       +---------------+--------------+
+-------------------+                       |
                                            v
                                     repositories -> Room

+-------------------+       +------------------------------+
| HowLongToBeat     | ----> | separate HLTB HTTP client     |
| completion times  |       | parser, matcher, cache       |
+-------------------+       +---------------+--------------+
                                            |
                                            v
                                     HltbRepository -> Room
```

The Android client is usable without the cloud path. For configured readers,
Settings uses the authenticated `readPresence` HTTPS endpoint rather than direct
Firestore access. Ordinary reads retain their bounded recent-window behavior;
historical access requires an explicit range and remains separate from the
ordinary read cursor.

## Scheduling and background flows

```text
+---------------------------+
| Backlogium.onCreate()     |
+-------------+-------------+
              |
      +-------+-------------------------+
      |                                 |
      v                                 v
SteamSyncWorker                    ReconciliationWorker
every 15 min                       every 7 days
connected network                  charging + unmetered network
      |                                 |
      |                                 +--> cold-tier achievements
      |                                      oldest first; resumable
      +--> Steam library/profile             deferred or forced from Settings
      +--> tiered achievement refresh
      +--> sessions, XP, diagnostics
      +--> enqueue genre enrichment

Settings -> expedited "Sync now"
Settings -> forced "Full achievement refresh"
Library  -> HLTB refresh / cancel / candidate selection
App foreground -> live status check -> PresenceService (60s cadence)
```

The tiered achievement path keeps hot and warm games in the normal sync, caps
missing-data work, and defers cold games to reconciliation. The implementation
is merged; four hardware-dependent checks remain tracked in
[issue #52](https://github.com/cnhy-nero-diskard/Backlogium/issues/52): unlock
latency, real sync duration, real constraint gating/resume, and cold-game
rarity-standing rendering.

## Sync write integrity

`SteamSyncWorker` is restructured into three phases so that no partial write can
land and no two polls can double-count the same increase:

```text
PHASE 1  fetch       network only, no writes: owned games, presence summary,
                     Steam level, achievement payloads
PHASE 2  compute     pure, no I/O: diff against last-known state, session
                     actions, day deltas, provisional gamification result
PHASE 3  commit      one non-cancellable Room transaction: re-reads the
                     baselines phase 2 diffed from, recomputes the delta
                     against them, then writes sessions + game baselines +
                     daily progress + profile fields together or not at all
PHASE 4  derived     outside the transaction, via the existing DataStore
                     write-ahead protocol: gamification XP/quests/streaks,
                     refused and recomputed if rule config changed since
                     phase 2 read it
```

Two coordinators sit around this, at different layers:

- **`SteamSyncCoordinator`** — a process-wide `Mutex` (`withLock`) around a whole
  poll, reconciliation pass, or historical daily-progress correction. A "Sync
  now" tap that overlaps a running poll waits behind it instead of spending a
  second round of Steam requests; the shared boundary also prevents the
  backfill's session-ledger snapshot from racing a raw sync commit. Phase 3's
  fresh Room re-read remains the correctness fallback for concurrent observers.
- **`DerivedStateWriteCoordinator`** — serializes derived-state writes across
  the sync, backup restore, rule-config change, and playtime-backfill call
  sites, since all four go through the same non-reentrant WAL protocol
  (`GamificationUpdater.persistWithinProtocol`).

`Game` and `PlayerProfile` rows are written through field-scoped queries keyed
to which domain owns each column (Steam-owned vs. app-owned for `Game`; sync
status / identity / gamification aggregates / history-import state for
`PlayerProfile`), rather than a whole-row upsert — see `openspec/changes/
archive/2026-08-15-auditfix-sync-write-integrity/design.md` for the full column
ownership map and the reasoning behind each rejected alternative. Rule
configuration (`VersionedRuleConfig`) and stored derived values
(`VersionedDerivedPersistence`) each carry a monotonic version so a superseded
write is detected and refused rather than silently applied. Achievements Steam
stops returning are tombstoned during reconciliation rather than deleted.

## Independent cloud writer

```text
Steam Web API -> Firebase pollPresence (scheduled, 1/min) --Admin SDK writes--> Firestore
Android Settings -> CloudPresenceRepository --Bearer HTTPS--> readPresence --Admin SDK query--> Firestore
```

The function writes the current observation and append-only state transitions;
it does not calculate sessions, playtime, streaks, or XP. The separate
`readPresence` HTTPS function checks its bearer token before any Firestore read;
`firestore.rules` continues to deny direct client reads and writes. A positionless
first read remains limited to 31 days. The opt-in `mode=range` lookup returns the
earliest retained evidence, a current-state snapshot, and server `readAt`; explicit
`from`/`through` requests return at most 250 transitions per page with a fixed end.
The app stages historical pages durably and requires explicit continuation after
each bounded batch. It only re-files after the chosen range is complete, and only
an independent user-confirmed cutoff can authorize transfer of eligible minutes
from an already-imported owned-game balance. Re-dating existing sessions and
transferring pre-data imported minutes are separate effects; neither changes the
combined credited minutes, XP, levels, or Steam totals. See
[`functions/README.md`](../functions/README.md) for API bounds and the cost envelope.

## Repository map

```text
Backlogium/
|-- app/src/main/java/com/example/backlogium/
|   |-- ui/          Compose screens, navigation, ViewModels, theme
|   |-- data/        Room, DataStore, repositories, APIs, backup
|   |-- domain/      pure business rules and presentation inputs
|   |-- work/        WorkManager workers and foreground presence service
|   |-- di/          Hilt providers and bindings
|   `-- MainActivity.kt / BacklogiumApp.kt
|-- gamification/    standalone XP and rarity calculations
|-- functions/       TypeScript Firebase presence poller and bounded reader; outside Gradle
|-- openspec/specs/  canonical product and behavior requirements
|-- openspec/changes/active implementation plans and verification notes
`-- docs/            forward-facing UI and architecture references
```

Implemented product surfaces are summarized in the root
[README](../README.md). The app-side bounded reader and confirmed historical
re-file are implemented. Remaining cloud-facing product work is the OBS Browser
Source overlay.
