# Backlogium

Backlogium is an offline-first Android companion for a Steam library. It turns
playtime, achievements, rarity, completion estimates, daily quests, and streaks
into one local progression system.

The app is built for day-to-day personal use: connect a Steam account, sync the
library, review HowLongToBeat matches, organize custom collections, inspect
analytics, mark Focus games, import pre-install Steam history once, and keep a
local backup. An independent presence poller records server-side observations to
Firestore while the phone is asleep; the app itself does not read that data yet,
and works entirely without it. An OBS overlay remains a roadmap item.

## Status snapshot — 2026-08-12

The current branch contains the implemented offline-first Android product loop,
including the collection UI behavior fixes merged on August 12 and the tiered
achievement-sync optimization merged on August 11. The source is actively
maintained; it is not presented here as a claim that every device-only check or
future cloud consumer is complete.

| Area | Current state |
|---|---|
| Android client | Five top-level destinations: Home, Library, History, Analytics, and Settings, with onboarding, game detail, HLTB review, collections, and diagnostics as pushed surfaces. |
| Local product loop | Implemented: Steam onboarding/sync, local sessions and XP, quests/streaks, HLTB data, genres, custom collections, analytics, live monitoring, backups, and settings. |
| Achievement sync | Tiered hot/warm/cold/never selection is merged. Normal sync is bounded; cold-tier reconciliation runs weekly on charging + unmetered network or from the forced Settings action. |
| Cloud presence | The one-minute Firebase Admin writer and Firestore transition log are implemented. The Android app and OBS do not consume that log yet; client access is denied by the current Firestore rules. |
| Verification | Kotlin/JVM tests, Android unit tests, instrumentation tests, compile checks, and function builds are available. Four hardware-dependent achievement-sync checks remain tracked in [#52](https://github.com/cnhy-nero-diskard/Backlogium/issues/52). |

## What Works Today

- Steam onboarding from inside the app: API key, SteamID64, or profile URL.
- Library sync from Steam Web API, including owned games, recent playtime, profile
  summary, player level, achievements, achievement schema, and live presence.
- HowLongToBeat lookups with batch refresh, ambiguous-match review, and per-game
  completionist progress.
- XP, levels, quests, streaks, Focus games, Steam history import, and rarity-weighted
  achievement XP.
- Custom collections with descriptions, accents, display order, completion/deadline/queue modes,
  pacing summaries, HLTB time-basis estimates, and manual member completion marks.
- Analytics with rolling or calendar windows, daily playtime, streak/session/time-of-day insights,
  achievement rarity, and most-played games.
- Genre enrichment and Library filtering, game-detail current-player counts, and rarity standing.
- Home, Library, History, Analytics, Settings, game detail, onboarding, collection, diagnostics,
  and HLTB review screens.
- Foreground now-playing tracking with an ongoing notification while a Steam game is
  detected.
- Local backup and restore for the app's tracked data, including optional rolling automatic
  snapshots.
- Sync diagnostics with persisted run timing, request breakdowns, presence decisions, and
  achievement-refresh tier counts.
- Fully local Room/DataStore persistence. Steam credentials are encrypted at rest
  with an Android Keystore-backed key.
- A scheduled Cloud Function polling Steam presence every minute and appending game
  transitions to Firestore, independent of whether the phone is running. Nothing
  reads that log yet — see [`functions/README.md`](functions/README.md).

## Stack

- **Data source:** Steam Web API
- **Client:** Android, Kotlin, Jetpack Compose, Room, Hilt, WorkManager
- **Local storage:** Room for game/session/achievement data; Preferences DataStore
  for encrypted Steam credentials and app state
- **Gamification:** standalone `:gamification` JVM module
- **Extra data:** HowLongToBeat completionist times used to taper playtime XP
- **Cloud:** Firebase Cloud Functions (Node/TypeScript) on a one-minute schedule,
  writing a presence log to Firestore in `asia-southeast1`
- **Not implemented yet:** app-side backfill from the presence log and an OBS
  Browser Source overlay

## Architecture

```text
Steam Web API --\
HowLongToBeat ----> Android app (Compose + repositories + Room/DataStore)
                    |-> WorkManager sync, HLTB refresh, genre enrichment,
                    |   reconciliation, local backup/restore, gamification
                    \-> planned: app-side backfill from the presence log below

Steam Web API ----> Cloud Function (1/min) -> Firestore presence log
                                              \-> planned OBS browser overlay
```

The app runs on-device: it pulls from the Steam Web API, gathers HowLongToBeat
completion estimates, stores data locally, and computes XP locally. It does not
depend on the cloud for anything.

The Cloud Function is a separate, independent writer. It records presence
observations only — never sessions, playtime, or XP — so that the on-device engine
remains the single author of derived values. Nothing consumes its output yet; the
app-side backfill and the browser-source overlay are future work.

For the source-oriented view of the layers, data stores, workers, and external
boundaries, see the [ASCII architecture map](docs/architecture-map.md).

### Data-Source Boundary

Repositories expose domain models. Room entities stay inside `data/`. Nothing
under `ui/` imports a storage type: no `data.local.entity.*`, and no
`SettingsDataStore` in a ViewModel. Settings go through `SettingsRepository`.

Checkable from a shell — matching on `import` skips prose mentions in KDoc, and
`--exclude-dir` skips the documented diagnostics exception:

```bash
grep -rn "^import .*\(data\.local\.entity\|SettingsDataStore\)" \
  app/src/main/java/com/example/backlogium/ui/ --exclude-dir=diagnostics
```

Two deliberate exceptions:

- `HltbCandidate` (`data.hltb`) crosses the boundary as a plain serializable class
  because it is exactly the shape the review surface needs.
- `ui/diagnostics/` reads `DiagnosticsDao` directly and renders `SyncRun`,
  `RequestBreakdown`, and `PresenceDecision` verbatim. It is a developer-facing debug
  screen whose purpose is to show the stored rows as stored, so an identity mapping to
  look-alike domain models would only add a layer that can misrepresent what is being
  debugged. Scoped to this package; writes still go through `SyncRunRecorder`.

`ui/home/HomeViewModel.kt` is a known outstanding breach rather than an exception —
`CollectionRepository` exposes entities across its public API, and mapping at that
boundary is deferred work. See `CLAUDE.md` for the detail.

## Visual Identity

Material 3, custom dark-first color scheme. Android dynamic (wallpaper-derived) color is
**off** (`BacklogiumTheme(dynamicColor = false)`), so this palette is the app's look on every
device. A light scheme is retained for system light-mode users.

The palette is organized by family and by what each one *means* — not a full token dump. Exact
values for every token, including light-scheme counterparts, live in `ui/theme/Color.kt`, which
is the source of truth; per-screen usage is documented in `docs/ui-screens-descriptor.md`.

| Family | Anchor hex (dark) | Meaning |
|---|---|---|
| Surfaces (charcoal/navy) | `#10141C` bg, `#171C26` surface, `#232A38` surface-variant | App chrome. Text on dark: `#E4E8F0` primary, `#AEB6C4` secondary. |
| Gold accent | `Gold #E0A83A` on `#241A00` | Maps to Material 3 `primary` — ordinary emphasis (buttons, progress, selected state), not milestone-only. |
| Steel-blue | `SteelBlue #7FA6C9` (secondary), `SteelBlueLight` (tertiary) | Owns the "in game right now" lane; hand-tuned `tertiaryContainer` (`#243B4C` / on `#CFE4F0`) for the now-playing card. |
| Live presence | `PlayingIndicator #4ADE80` | The Library row's "currently playing" dot. Live presence only. |
| Derived accents | `GoldOverrun #8A431C`, `DeadlineWarning #FFB454` | Completion overshoot and due-soon warnings — inside the gold hue family rather than a new one. |
| Rarity halo | `RarityCommon #8A93A3`, `RarityUncommon #6FAE7A`, `RarityEpic #A579D6` | Achievement rarity glow. RARE and LEGENDARY reuse `SteelBlue` and `Gold` rather than adding two more accents. |
| Collection tints | `CollectionTeal`, `CollectionRose`, `CollectionCoral` | Muted card tints; deliberately not vivid enough to compete with gold or green. |
| Light scheme | `Gold #7A5A00`, `SteelBlue #2F5B7C`, surfaces `#FBF8F1` / `#EDE6D6` | Same families, re-anchored for contrast on cream surfaces. Every dark token has a `*Light` counterpart. |

**The rule is hue territory, not a milestone reservation.** Gold is `primary` and carries ordinary
emphasis everywhere — it is not held back for level-ups and streaks. What's actually enforced is
that nothing else may *be* gold: the rarity ramp reuses `Gold` at LEGENDARY instead of minting a
second gold, so gold keeps one meaning instead of splitting across two shades. Steel-blue owns the
in-game lane, green means live presence and stays vivid enough that it can't be mistaken for
`RarityUncommon`'s muted sage, and every derived accent stays inside an existing hue family rather
than claiming new territory.

## App Surfaces

- **Home:** live now-playing panel, level/XP progress, today's quest, streak,
  Steam account summary, Steam history import, manual sync, and collection cards.
- **Library:** searchable owned-games list split into Focus and Your games,
  independent sorting, completion progress, XP badges, live-game indicators, HLTB
  refresh controls, multi-select lookup, and genre filtering.
- **History:** day-grouped play sessions, daily quest results, achievement unlocks,
  expandable game/session detail, and older-history pagination.
- **Analytics:** selectable rolling/calendar windows, daily playtime chart, streak and
  session summaries, time-of-day pattern, rarity breakdown, and most-played games.
- **Settings:** sync controls, tiered achievement refresh, genre status, live monitor,
  backup/restore and automatic snapshots, history import, diagnostics, and editable
  gamification rules.
- **Collections:** pushed from Home for collection overview/editing, pacing/deadline
  planning, member management, ordering, and game-detail overlays.
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
.\gradlew.bat :app:compileDebugKotlin --offline
.\gradlew.bat :gamification:test :app:testDebugUnitTest --offline
npm.cmd --prefix functions run build
```

For Android Studio, open the repository root and run the `app` configuration on a
device or emulator. Instrumentation tests and hardware-dependent sync checks need
a connected Android device/emulator; a passing local unit/compile run does not
replace those checks.

## Remaining roadmap

The offline-first Android app is the current product. It tracks real Steam
sessions, awards XP from playtime and achievements, shows level/quests/streaks and
history, reviews HLTB matches, monitors live presence, provides analytics and
collections, and supports local backup/restore. A cloud presence poller runs
alongside it, recording observations the phone cannot observe.

Remaining roadmap items:

- App-side backfill: read the Firestore presence log and fill in sessions missed
  while the phone was asleep or killed. Requires deciding how the app authenticates,
  since Firestore rules currently deny all client access.
- Static OBS Browser Source overlay backed by the `players/{steamId}` document.
- Overlay polish for quest completions, streak milestones, and level-ups.
- Close the four device-dependent achievement-sync checks tracked in
  [#52](https://github.com/cnhy-nero-diskard/Backlogium/issues/52).

## Security Notes

- Do not commit `local.properties`, API keys, keystores, or backup files containing
  personal library data.
- If a Steam API key is exposed, rotate it at
  <https://steamcommunity.com/dev/apikey>.
- Security reporting details are in [SECURITY.md](SECURITY.md).

## Project References

- UI screen reference: [docs/ui-screens-descriptor.md](docs/ui-screens-descriptor.md)
- Architecture map: [docs/architecture-map.md](docs/architecture-map.md)
- OpenSpec specs: [openspec/specs](openspec/specs)
- Current and archived changes: [openspec/changes](openspec/changes)
