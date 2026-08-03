# Backlogium

Backlogium is an offline-first Android companion for a Steam library. It turns
playtime, achievements, rarity, completion estimates, daily quests, and streaks
into one local progression system.

The app is built for day-to-day personal use: connect a Steam account, sync the
library, review HowLongToBeat matches, mark Focus games, import pre-install Steam
history once, and keep a local backup. Cloud sync and an OBS overlay remain roadmap
items; the phone app works without either.

## What Works Today

- Steam onboarding from inside the app: API key, SteamID64, or profile URL.
- Library sync from Steam Web API, including owned games, recent playtime, profile
  summary, player level, achievements, achievement schema, and live presence.
- HowLongToBeat lookups with batch refresh, ambiguous-match review, and per-game
  completionist progress.
- XP, levels, quests, streaks, Focus games, Steam history import, and rarity-weighted
  achievement XP.
- Home, Library, History, Settings, game detail, onboarding, and HLTB review screens.
- Foreground now-playing tracking with an ongoing notification while a Steam game is
  detected.
- Local backup and restore for the app's tracked data.
- Fully local Room/DataStore persistence. Steam credentials are encrypted at rest
  with an Android Keystore-backed key.

## Stack

- **Data source:** Steam Web API
- **Client:** Android, Kotlin, Jetpack Compose, Room, Hilt, WorkManager
- **Local storage:** Room for game/session/achievement data; Preferences DataStore
  for encrypted Steam credentials and app state
- **Gamification:** standalone `:gamification` JVM module
- **Extra data:** HowLongToBeat completionist times used to taper playtime XP
- **Planned:** Firestore cloud sync and an OBS Browser Source overlay

## Architecture

```text
Steam Web API --\
HowLongToBeat ----> Android app (Room + DataStore + gamification engine)
                    |-> local backup / restore
                    \-> planned Firestore sync -> OBS browser overlay
```

The current app runs on-device: it pulls from the Steam Web API, gathers
HowLongToBeat completion estimates, stores data locally, and computes XP locally.
The cloud-sync layer and browser-source overlay are future work.

### Data-Source Boundary

Repositories expose domain models. Room entities stay inside `data/`. Nothing
under `ui/` imports a storage type: no `data.local.entity.*`, and no
`SettingsDataStore` in a ViewModel. Settings go through `SettingsRepository`.

Checkable from a shell:

```bash
grep -rn "data.local.entity\|SettingsDataStore" app/src/main/java/com/example/backlogium/ui/
```

One deliberate exception: `HltbCandidate` (`data.hltb`) crosses the boundary as a
plain serializable class because it is exactly the shape the review surface needs.

## App Surfaces

- **Home:** live now-playing panel, level/XP progress, today's quest, streak,
  Steam account summary, Steam history import, and manual sync.
- **Library:** searchable owned-games list split into Focus and Your games,
  independent sorting, completion progress, XP badges, live-game indicators, HLTB
  refresh controls, and multi-select lookup.
- **History:** day-grouped play sessions, daily quest results, achievement unlocks,
  expandable game/session detail, and older-history pagination.
- **Settings:** sync schedule, live monitor controls, backup/restore, and editable
  gamification rules.
- **Game detail:** game summary, store art, HLTB lengths, current player count,
  achievement progress, rarity tiers, and completion banner.
- **HowLongToBeat review:** candidate picker for ambiguous HLTB matches.

## Gamification Rules

### Playtime XP

```text
gameXp(M, T) = xpPerMinute * (Z / (k+1)) * (1 - (1 - min(M,Z)/Z)^(k+1))

where:
  T = completionistAverageMinutes
  Z = 2.0 * T
  k = 4
```

Playtime XP has diminishing returns per game, relative to that game's
HowLongToBeat completionist-average length. Grinding one game well past a
completionist's expected time stops paying out instead of scaling forever.

Games with no HowLongToBeat data fall back to the flat, uncapped rate. Total XP
feeds the same level curve: `xpAt(L) = 50 * (L - 1) * L`.

The constants are tunable. Full rules, rationale, and edge cases live in the
[`gamification` spec](openspec/specs/gamification/spec.md).

### Achievement XP

Achievement XP is additive and counts unlocked Steam achievements, weighted by
rarity tier from Steam's global unlock percentage:

```text
totalXp = sum(gameXp(g.minutesPlayed, g.completionistAverageMinutes) over games)
          + achievementXp(unlockedAchievements)
```

One unified XP pool is used. Locked achievements contribute nothing, and rarer
unlocks are worth more.

## Setup

### Steam Credentials

You do not need to edit any files or rebuild to connect a Steam account. On first
launch, the app shows a two-step onboarding flow:

1. **API key:** paste a Steam Web API key from
   <https://steamcommunity.com/dev/apikey>.
2. **SteamID:** paste a raw 17-digit SteamID64 or a Steam profile URL. Vanity URLs
   are resolved through the Steam Web API.

Credentials are encrypted at rest with an Android Keystore-backed key and stored
in encrypted DataStore. The API key is masked wherever it is displayed.

Your Steam profile and game details must be public for playtime to be visible.

### Optional Build-Time Seed

For local development, `local.properties` can pre-seed credentials so a fresh
install skips onboarding:

```properties
steam.apiKey=YOUR_STEAM_WEB_API_KEY
steam.steamId=YOUR_STEAMID64
```

`local.properties` is git-ignored and must never be committed. Seeded values are
imported into the encrypted store once, only when the store is empty.

## Build And Test

Use the Gradle wrapper from the repository root:

```powershell
.\gradlew.bat testDebugUnitTest
```

For Android Studio, open the repository root and run the `app` configuration on a
device or emulator.

## Roadmap

The offline-first Android app is the current product. It tracks real Steam
sessions, awards XP from playtime and achievements, shows level/quests/streaks and
history, reviews HLTB matches, monitors live presence, and supports local
backup/restore.

Remaining roadmap items:

- Firestore sync for selected app state.
- Static OBS Browser Source overlay backed by the same synced state.
- Overlay polish for quest completions, streak milestones, and level-ups.

## Security Notes

- Do not commit `local.properties`, API keys, keystores, or backup files containing
  personal library data.
- If a Steam API key is exposed, rotate it at
  <https://steamcommunity.com/dev/apikey>.
- Security reporting details are in [SECURITY.md](SECURITY.md).

## Project References

- UI screen reference: [docs/ui-screens-descriptor.md](docs/ui-screens-descriptor.md)
- OpenSpec specs: [openspec/specs](openspec/specs)
- Current and archived changes: [openspec/changes](openspec/changes)
