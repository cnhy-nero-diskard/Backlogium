# Backlogium architecture map

Status snapshot: 2026-08-12

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
|          Home | Library | History |       Onboarding                    |
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
|  Room database (schema version 14)                                     |
|    games                 sessions              daily_progress            |
|    player_profile       hltb_data              achievements              |
|    game_achievement_sync                         collections             |
|    collection_members   game_genre_cache                               |
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

The Android client is usable without the cloud path. It currently does not read
the Firestore presence log.

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

## Independent cloud writer

```text
+-------------------+       +--------------------------+       +-------------+
| Steam Web API     | ----> | Firebase scheduled       | ----> | Firestore   |
| presence endpoint |       | pollPresence (1/min)     |       | players/{id}|
+-------------------+       | Node 22 / TypeScript     |       | /presence   |
                            | Admin SDK                |       | transitions |
                            +--------------------------+       +-------------+
```

The function writes the current observation and append-only state transitions;
it does not calculate sessions, playtime, streaks, or XP. `firestore.rules`
currently denies client reads and writes, so this path is intentionally a
separate writer with no Android consumer yet. See
[`functions/README.md`](../functions/README.md) for deployment and monitoring.

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
|-- functions/       TypeScript Firebase presence poller; outside Gradle
|-- openspec/specs/  canonical product and behavior requirements
|-- openspec/changes/active implementation plans and verification notes
`-- docs/            forward-facing UI and architecture references
```

Implemented product surfaces are summarized in the root
[README](../README.md). The remaining product roadmap is the app-side Firestore
backfill and an OBS Browser Source overlay; the cloud writer currently exists to
make those future consumers possible.
