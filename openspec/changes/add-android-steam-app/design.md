# Design — Android Steam app: sync + UI (offline MVP)

## Context

- Starting point: default Compose template, `com.example.backlogium`, minSdk 33,
  compileSdk 36, Kotlin 2.0.21, Compose BOM 2024.09. No data layer of any kind.
- Single user, single Steam account, one device. No cloud in this change.
- The Steam Web API is **pull-only and stateless about sessions.** Everything the
  app "knows" about play sessions is derived locally.

## Architecture

```
app/src/main/java/com/example/backlogium/
├─ data/
│  ├─ remote/     SteamApi (Retrofit) + DTOs (OwnedGames, SteamLevel)
│  ├─ local/      Room: entities, DAOs, BacklogiumDatabase
│  └─ repo/       GameRepository, SessionRepository, ProfileRepository
├─ domain/
│  └─ SessionDiffer        poll-and-diff → Session records
├─ work/         SteamSyncWorker (WorkManager) + scheduling
├─ di/           Hilt modules
└─ ui/
   ├─ home/  library/  history/   screens + ViewModels
   └─ theme/                       (existing)

:gamification    ← separate pure module (add-gamification-engine), consumed here
```

Data flows one direction: **Worker → Steam → Differ → Room → `:gamification` engine
→ Room → Flows → UI.** The app feeds tracked minutes / per-day totals / goal targets
into the engine and writes the returned XP/level, quest, streak, and progress back to
Room. The UI never calls Steam directly and never computes rules ad hoc; it observes
Room. This keeps the app fully functional offline (last synced state) and makes the
eventual Firestore mirror a matter of reading the same Room tables.

## Key decisions

### 1. Steam-direct, key from `local.properties` → `BuildConfig`
For a personal single-user app there is no backend, so the app calls Steam directly.
The API key and SteamID64 are read from `local.properties` (git-ignored) and surfaced
through `BuildConfig` fields, never hardcoded or committed.

- Trade-off: a distributed APK would leak the key. Acceptable here (personal build);
  a backend is the migration path if distribution is ever wanted. Recorded as a risk.

### 2. Poll-and-diff session synthesis
`WorkManager` periodic worker (15-min minimum interval, the platform floor) fetches
`GetOwnedGames` with `include_appinfo=1` & `include_played_free_games=1`.

```
For each game in the poll:
  delta = playtime_forever(now) − lastPlaytime(stored)
  if delta > 0:
     if no open session for this game: open one (start ≈ previous poll time)
     extend the open session by `delta` minutes; set lastIncreaseAt = now
     lastPlaytime = playtime_forever(now)
  else (delta == 0):
     if an open session exists: CLOSE it (end = lastIncreaseAt)
```

- **First-sync baseline:** on the very first successful poll, store every game's
  `playtime_forever` as `lastPlaytime` and create **zero** sessions. Only deltas
  observed *after* the baseline become sessions. Prevents a fabricated
  hundreds-of-hours "session" on day one.
- **Coarseness:** with a 15-min cadence, session start/end are accurate to ±1 poll
  and plays shorter than the interval between two polls can be missed. This is the
  accepted MVP trade for zero battery/notification cost.
- **Edge cases handled defensively:** `playtime_forever` decreasing (family sharing /
  refunds) → treat as no-delta, don't emit negative sessions; a game disappearing
  from the list → leave its stored state untouched; a private profile / empty
  response → surface a "sync failed: profile private?" state, keep last good data.

### 3. WorkManager scheduling
- `PeriodicWorkRequest`, 15-min interval, `NetworkType.CONNECTED`, `KEEP` existing
  work on reschedule, backoff on failure. Survives reboot via WorkManager's own
  persistence (add `RECEIVE_BOOT_COMPLETED`-free — WorkManager reschedules itself).
- A manual "Sync now" action enqueues a one-time expedited request.

### 4. Room is the single source of truth
UI state comes only from Room `Flow`s. Sync updates rows; the app calls the
`:gamification` engine to recompute derived values and writes them back. No business
logic in Composables.

### 5. Gamification via the `:gamification` module (Phase 2 consumed, not owned)
The rule logic and its tunable `RuleConfig` live in the separate
`add-gamification-engine` change. This app is a **consumer**: on each sync (and on day
rollover) it builds the engine's inputs from Room and persists the outputs.

```
Room ──build inputs──▶ :gamification ──outputs──▶ Room
  per-game tracked minutes  │  Gamification.xp(...)     totalXp, level → PlayerProfile
  DailyProgress day totals  │  Gamification.quest(...)  questMet      → DailyProgress
  ordered quest results     │  Gamification.streak(...) current/longest → PlayerProfile
  goal target + playtime    │  Gamification.goalProgress(...) fraction → (derived for UI)
```

The app owns: the current date/timezone (injected into the engine — the engine has no
clock), goal tagging + target editing (persistence), and storing results. Quest/streak
are evaluated against `DailyProgress` rows keyed by local calendar date, recomputed on
each sync and on day rollover.

**Two distinct playtime inputs (do not conflate):**
- **XP** is fed the sum of **post-baseline `Session.minutes`** — only playtime the app
  actually tracked. This is why the first-sync baseline (which emits zero sessions)
  does not dump thousands of XP on day one.
- **Goal progress** is fed **`Game.playtimeForever`** — the *total* including
  pre-baseline hours — so a backlog game you were already 12h into a 20h target shows
  60% immediately, not 0%. (Locked decision: `progress = playtimeForever / target`.)

**Per-day attribution:** each poll's per-game delta is attributed to the poll's local
calendar date. A session straddling midnight splits across days at poll granularity —
accepted as coarse (consistent with the ±15-min session accuracy).

### 6. Dependency injection: Hilt
Idiomatic for Android, scales to the cloud phase. Uses KSP (not kapt) to stay fast.
Manual DI was considered; Hilt chosen because the graph (Retrofit, Room, WorkManager
`HiltWorker`, repos) is large enough to benefit.

## Data model (Room)

```
Game            appId(PK) · name · iconUrl · playtimeForever · playtime2weeks
                · lastPlaytime · isGoal · targetMinutes? · lastSyncedAt
Session         id(PK) · appId(FK) · startAt · endAt · minutes · open(bool)
DailyProgress   date(PK, local) · minutesPlayed · goalMinutesPlayed · questMet
PlayerProfile   id(single row) · steamId · steamLevel · totalXp · level
                · currentStreak · longestStreak · lastSyncAt · lastSyncError?
```

Chosen to mirror cleanly to small Firestore documents later (one doc per profile +
compact recent-sessions, not full history dumps) per the README cost note.

## Risks / open questions

- **API key in APK** (accepted for personal build; backend is the escape hatch).
- **15-min coarseness** may feel laggy for someone wanting live stats — but live
  stats are the overlay's job (out of scope), so acceptable here.
- **Public-profile dependency** — if the user's Steam profile/game details are
  private, the app can't see playtime. Needs a clear onboarding message.
- **Level curve + XP rate are guesses** — deliberately isolated in `RuleConfig` so
  tuning after a few days of real data costs nothing.
- **Package rename** (`com.example.backlogium`) deferred — confirm before first
  "real" release to avoid a Play Store applicationId lock-in.
