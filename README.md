# Gaming Session Tracker (Android) — Steam playtime, gamified

A gamified companion app that tracks your Steam playtime and progress and turns it
into XP, levels, quests, and streaks. Today it is a self-contained, **offline-first
Android app**; cloud sync and a live OBS stream overlay are planned (see the roadmap).

## Stack

- **Data source:** Steam Web API
- **Client:** Android (Kotlin, Jetpack Compose, Room DB, Hilt, WorkManager)
- **Local storage:** Room (game/session/achievement data) + Preferences DataStore
  (encrypted Steam credentials, app state)
- **Gamification:** a standalone `:gamification` JVM module (pure Kotlin, unit-tested)
- **Extra data:** HowLongToBeat completionist times (used to taper playtime XP)
- **Planned:** cloud sync (Firestore) + an OBS Browser Source overlay — *not yet built*

## Architecture

```
Steam Web API ─┐
HowLongToBeat ─┴→ Android app (Room + DataStore, gamification engine)   ← today
                     └→ (planned) Firestore sync → OBS browser overlay
```

The current app runs entirely on-device: it pulls from the Steam Web API (and scrapes
HowLongToBeat for completionist times), stores everything locally, and computes XP
locally. The cloud-sync layer and the browser-source overlay below are the remaining
roadmap items — the phone does not yet talk to any backend.

## Roadmap

Phases 1–4 are **done** — there is a working, offline-first Android app that tracks
real Steam sessions, awards XP (playtime *and* achievements), and shows level/quests/
streaks/history. Phases 5–6 (cloud sync + OBS overlay) are the remaining work.

### Phase 1 — Steam API foundation ✅
Goal: prove reliable access to your own data before building anything on top.

- [x] Get a Steam Web API key + find SteamID64 (now captured via in-app onboarding)
- [x] Steam calls wired: `GetOwnedGames`, `GetRecentlyPlayedGames`, `GetPlayerAchievements`, `GetSchemaForGame`, `ResolveVanityURL`, player summaries/level
- [x] Refresh strategy decided: WorkManager-scheduled polling (`SteamSyncWorker`) — Steam doesn't push
- **Checkpoint:** app correctly reflects current game + playtime

### Phase 2 — Gamification model ✅
Goal: lock the rules before they're baked into a schema.

- [x] XP rules: playtime-based, with diminishing returns per game (see
  [Gamification rules](#gamification-rules-locked) below)
- [x] Achievement XP: additive term weighted by Steam rarity tier (now **merged**)
- [x] "Goal games": manual tag with a per-game target (set from the Library screen)
- [x] Quests/streaks: daily playtime target, current/longest streak, milestone at every 7 days
- **Checkpoint:** rules live in the tested `:gamification` module and in
  [`openspec/specs/gamification`](openspec/specs/gamification/spec.md)

### Phase 3 — Android app: data layer + core logic ✅
- [x] Android project (Kotlin, Jetpack Compose, Hilt)
- [x] Room DB: games, sessions, achievements, daily stats, XP/streak state
- [x] Steam + HowLongToBeat data pulled and mapped into the gamification engine
- [x] Fully offline-first — everything on-device, no cloud
- **Checkpoint:** app installs and tracks real sessions

### Phase 4 — Android app: UI/UX ✅
- [x] Home: level/XP, today's quest, streak, "now playing", Steam-history import, sync + credentials
- [x] Library: goal games with progress bars vs. backlog; tap to set/edit a goal; per-game detail with achievements
- [x] History: recent sessions + per-day play totals and quest results
- [x] HowLongToBeat match-review screen for ambiguous matches
- **Checkpoint:** app is usable day-to-day

### Phase 5 — Cloud sync layer (Firebase) — planned
- [ ] Set up Firestore, mirror the Room schema (small, focused documents — not full history dumps)
- [ ] Push local state to Firestore on change via real-time listeners (not polling)
- [ ] Set a billing alert (~$1) as a safety net
- **Checkpoint:** phone data appears live in the Firebase console while playing

### Phase 6 — OBS overlay (final deliverable) — planned
- [ ] Build overlay as a static HTML/JS page (Firebase Hosting)
- [ ] Listen to the same Firestore doc the phone writes to; render XP bar, current quest, streak, session timer
- [ ] Add as an OBS Browser Source, position/style it
- [ ] Polish pass: animations for quest completions, streak milestones
- **Checkpoint:** go live, watch stats update on stream in real time

## Gamification rules (locked)

### The formula

```text
gameXp(M, T) = xpPerMinute · (Z / (k+1)) · (1 − (1 − min(M,Z)/Z)^(k+1))

where:
  T = completionistAverageMinutes   (HowLongToBeat completionist average, in minutes)
  Z = 2.0 · T                       (zero point: 2× the average earns nothing further)
  k = 4                             (decay exponent)
```

Playtime XP has diminishing returns **per game**, relative to that game's HowLongToBeat
completionist-average length — grinding one game well past a completionist's expected
time stops paying out, instead of XP scaling with minutes forever.

- Early playtime earns close to the flat rate (1 XP/minute by default).
- At exactly the completionist average (`M = T`), the marginal rate has tapered to a
  very small fraction of the base rate (`0.5⁴ = 6.25%`).
- At twice the completionist average (`M = 2T`) and beyond, that game earns **zero**
  further XP.
- Games with no HowLongToBeat data fall back to the flat, uncapped rate.

Total XP is the sum of `gameXp(...)` across a player's library, feeding the same level
curve: `xpAt(L) = 50·(L−1)·L` (L2 at 100 XP, L3 at 300 XP, L4 at 600 XP, ...).

All constants (`xpPerMinute`, the zero-point multiple, the decay exponent, level base)
are tunable, not hardcoded. Full rules, rationale, and edge cases live in the
[`gamification` spec](openspec/specs/gamification/spec.md).

### Achievement XP (implemented)

Achievement XP is now folded into the engine. A second, additive term counts unlocked
Steam achievements, weighted by rarity tier (Steam's global unlock percentage —
`COMMON`/`UNCOMMON`/`RARE`/`EPIC`/`LEGENDARY`, each with its own fixed XP award):

```text
totalXp = Σ gameXp(g.minutesPlayed, g.completionistAverageMinutes) over games
          + achievementXp(unlockedAchievements)
```

One unified XP pool, not a separate achievement level — locked achievements contribute
nothing, and rarer unlocks are worth more. Per-game achievement progress (and a "game
completed" milestone at 100%) is shown on the game-detail screen.

## Setup

### Steam credentials (in-app onboarding)

You do **not** need to edit any files or rebuild to connect your Steam account. On
first launch (when no credentials are stored), the app shows a full-screen, two-step
onboarding flow:

1. **API key** — paste your Steam Web API key (there's an in-app link to
   <https://steamcommunity.com/dev/apikey>). It's entered behind a password field.
2. **SteamID** — either paste your raw 17-digit SteamID64, or paste your Steam
   profile URL and let the app resolve it (`.../profiles/<id>` is read directly;
   `.../id/<vanity>` is resolved via the Steam Web API).

Credentials are **encrypted at rest** with an Android Keystore-backed key and stored
in an encrypted DataStore — never in plaintext, never committed to source. The API
key is masked wherever it's shown. You can change credentials any time from the
**Steam account** card on the Home screen ("Edit" reopens onboarding).

Notes:

- Your Steam profile **and game details must be public** for playtime to be visible.
- If decryption ever fails (e.g. the Keystore key was invalidated), the app treats
  credentials as absent and re-shows onboarding rather than crashing.

### Optional: seed credentials at build time (`local.properties`)

For local development you can pre-seed credentials so you skip onboarding on a fresh
install. The build reads optional values from `local.properties` (git-ignored — never
committed) into `BuildConfig`:

```properties
steam.apiKey=YOUR_STEAM_WEB_API_KEY
steam.steamId=YOUR_STEAMID64
```

These are imported into the encrypted store **once**, only when it's empty. After that
the encrypted store is the single source of truth and `BuildConfig` is never consulted
again. Both are optional — leave them blank and just use onboarding.

## Notes

- Each phase should be testable on its own before moving to the next.
- Cost: for a single-user project like this, Firestore's free tier (50k reads / 20k writes per day) comfortably covers normal use — real-time listeners on the overlay avoid the polling patterns that actually rack up cost.
