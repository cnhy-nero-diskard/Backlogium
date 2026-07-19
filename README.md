# Gaming Session Tracker & OBS Overlay

A gamified companion app that tracks Steam playtime/progress, syncs to the cloud, and surfaces live stats on stream through an OBS overlay.

## Stack

- **Data source:** Steam Web API
- **Client:** Android (Kotlin, Jetpack Compose, Room DB)
- **Sync:** Firebase (Firestore, real-time listeners)
- **Output:** OBS Browser Source overlay (HTML/JS, hosted on Firebase Hosting)

## Architecture

```
Steam Web API → Cloud sync backend (Firestore) → Android app
                                                 → OBS browser source overlay
```

Phone and PC never talk to each other directly — Firestore keeps them in sync, so the app and the overlay both just read/write the same cloud state, from anywhere.

## Roadmap

### Phase 1 — Steam API foundation
Goal: prove reliable access to your own data before building anything on top.

- [ ] Get a Steam Web API key + find SteamID64
- [ ] Test calls: `GetOwnedGames`, `GetRecentlyPlayedGames`, `GetPlayerAchievements`, `IPlayerService/GetSteamLevel`
- [ ] Decide refresh/polling strategy (Steam doesn't push updates)
- **Checkpoint:** script that correctly prints current game + playtime

### Phase 2 — Gamification model (design, not code)
Goal: lock the rules before they're baked into a schema.

- [x] XP rules: playtime-based, with diminishing returns per game (see
  [Gamification rules](#gamification-rules-locked) below)
- [ ] "Goal games" definition — manual tag vs. auto-detected via time-to-beat threshold
- [ ] Quests/streaks — daily targets, weekly goals, what breaks a streak
- [ ] Sketch of session summary / focus score
- **Checkpoint:** one-page spec of rules → becomes the data model in Phase 3

### Phase 3 — Android app: data layer + core logic
- [ ] Set up Android project (Kotlin, Jetpack Compose)
- [ ] Room DB: sessions, games, quests, XP, streaks
- [ ] Service to pull Steam data and map it into the rule engine from Phase 2
- [ ] Fully offline first — no cloud yet
- **Checkpoint:** app installed on phone, accurately tracking real sessions for a few days

### Phase 4 — Android app: UI/UX
- [ ] Home screen: XP/level, active quest, streak
- [ ] Game library view: goal games with progress bars, backlog games
- [ ] Session history / stats view
- **Checkpoint:** app feels usable day-to-day, not just functional

### Phase 5 — Cloud sync layer (Firebase)
- [ ] Set up Firestore, mirror the Room schema (small, focused documents — not full history dumps)
- [ ] Push local state to Firestore on change via real-time listeners (not polling)
- [ ] Set a billing alert (~$1) as a safety net
- **Checkpoint:** phone data appears live in the Firebase console while playing

### Phase 6 — OBS overlay (final deliverable)
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
are tunable, not hardcoded. Full rules, rationale, and edge cases live in
[`openspec/changes/add-gamification-engine`](openspec/changes/add-gamification-engine).

### Achievement XP (proposed, not yet merged)

A separate proposal, [`openspec/changes/add-achievement-xp`](openspec/changes/add-achievement-xp),
adds a second, additive term for unlocked Steam achievements, weighted by rarity tier
(Steam's global unlock percentage — `COMMON`/`UNCOMMON`/`RARE`/`EPIC`/`LEGENDARY`, each
with its own fixed XP award):

```text
totalXp = Σ gameXp(g.minutesPlayed, g.completionistAverageMinutes) over games
          + achievementXp(unlockedAchievements)
```

One unified XP pool, not a separate achievement level — locked achievements contribute
nothing, and rarer unlocks are worth more. This part is still a proposal, reconciled to
compose with the per-game formula above but not yet folded into the base engine.

## Setup

### Steam credentials (`local.properties`)

The Android app reads your Steam Web API key and SteamID64 at build time from
`local.properties` (git-ignored — never committed) and exposes them through
`BuildConfig`. Add these two lines to `local.properties` at the repo root:

```properties
steam.apiKey=YOUR_STEAM_WEB_API_KEY
steam.steamId=YOUR_STEAMID64
```

- Get an API key at <https://steamcommunity.com/dev/apikey>.
- Find your SteamID64 (a 17-digit number) via your profile URL or a lookup tool.
- Both keys are optional for the build to succeed: if either is blank, the app
  builds normally and shows a "Steam not configured" state instead of crashing.
- Your Steam profile **and game details must be public** for playtime to be visible.

## Notes

- Each phase should be testable on its own before moving to the next.
- Cost: for a single-user project like this, Firestore's free tier (50k reads / 20k writes per day) comfortably covers normal use — real-time listeners on the overlay avoid the polling patterns that actually rack up cost.
