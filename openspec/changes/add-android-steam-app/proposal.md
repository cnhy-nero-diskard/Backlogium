# Add Android Steam app: sync + UI (offline MVP)

## Why

The repo today is a bare Jetpack Compose "hello world" scaffold. The README roadmap
splits the product across six phases, but the Android app (Phases 3–4) leans on the
Steam API foundation (Phase 1) and the gamification rules (Phase 2) as inputs. This
change delivers the **Android app itself** — Steam access, session synthesis,
persistence, and UI — consuming the rules from the separate `add-gamification-engine`
change so that Phase 2's logic stays locked and unit-tested in isolation. Together the
two changes collapse **Phases 1–4 into one shippable, self-contained Android app**
before any cloud or overlay work begins.

> **Depends on `add-gamification-engine`** for XP/level/quest/streak/goal-progress
> computation. This change persists and displays that engine's outputs; it does not
> reimplement the rules.

The README's Phase 3 is explicit: *"Fully offline first — no cloud yet"* and the
checkpoint is *"app installed on phone, accurately tracking real sessions for a few
days."* That is exactly the MVP boundary this proposal targets.

A non-obvious constraint drives the whole design: **the Steam Web API has no concept
of a "session" or "currently playing."** It only exposes cumulative `playtime_forever`
(and a rolling `playtime_2weeks`). Sessions must be *synthesized* by polling playtime
on an interval and diffing consecutive readings. The app is, at its core, a
poll-and-diff engine.

## What Changes

- **Steam integration (Phase 1):** Retrofit client for `GetOwnedGames` and
  `GetSteamLevel`, with the Steam Web API key and SteamID64 injected at build time
  from `local.properties` (never committed). A `WorkManager` periodic worker polls
  every ~15 minutes.
- **Session synthesis (Phase 1/3):** A `SessionDiffer` compares each poll's
  `playtime_forever` per game against the last stored value and emits `Session`
  records. First sync is **baselined** (records current totals without fabricating
  historical sessions); a session is **closed** after one poll with no playtime
  increase.
- **Gamification integration (Phase 2 consumer):** feeds tracked minutes / per-day
  totals / goal targets into the `gamification` engine and persists the returned
  XP/level, quest results, streaks, and goal progress. Goal tagging + target editing
  (a persistence/UI concern) live here; the rules themselves do not.
- **Data layer (Phase 3):** Room as the single source of truth (`Game`, `Session`,
  `DailyProgress`, `PlayerProfile`). UI observes Room via `Flow`. DataStore holds
  SteamID and settings.
- **UI (Phase 4):** Compose + Navigation with three screens — **Home** (level/XP,
  today's quest, streak), **Library** (goal games with progress bars, backlog), and
  **History** (recent synthesized sessions and daily stats).

## Impact

- **Affected specs (new capabilities):** `steam-sync`, `app-ui`. (`gamification`
  ships in `add-gamification-engine`.)
- **Affected code:** grows `app/` from the template into a layered app
  (`data/remote`, `data/local`, `data/repo`, `domain`, `work`, `ui`) and depends on
  the `:gamification` module. Adds dependencies: Retrofit + OkHttp +
  kotlinx.serialization, Room, WorkManager, DataStore, Navigation-Compose, Coil,
  Hilt (KSP).
- **Build config:** `local.properties` gains `steam.apiKey` / `steam.steamId`
  (already git-ignored); `build.gradle.kts` exposes them via `BuildConfig`.

## Non-goals (explicitly out of scope for this MVP)

- **Firestore / cloud sync (Phase 5)** and the **OBS overlay (Phase 6)** — the Room
  schema is designed to mirror cleanly to Firestore later, but no cloud code ships here.
- **Foreground-service / real-time tracking** — WorkManager's ~15-min cadence means
  sessions are coarse (±15 min) and sub-15-min plays may be missed. A live session
  timer and minute-accurate tracking are a documented post-MVP upgrade.
- **Gamification rule logic** — owned by `add-gamification-engine`; this change only
  wires it in and persists its outputs.
- **Achievements XP** (`GetPlayerAchievements`) — per-game schema, deferred.
- **Auto-detected goal games** (time-to-beat threshold / HowLongToBeat) — manual
  tagging only for MVP.
- **Multi-user / auth** — single-user, single Steam account.
- **Package rename** from `com.example.backlogium` — tracked as a follow-up, not
  blocking the MVP.
