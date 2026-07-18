# Tasks — Android Steam app: sync + UI (offline MVP)

> Depends on `add-gamification-engine` (the `:gamification` module). Build/verify that
> change first, or in parallel — this app consumes it.

## 1. Project & build setup
- [x] 1.1 Add dependencies to `libs.versions.toml` and `app/build.gradle.kts`:
      Retrofit + OkHttp + kotlinx.serialization, Room (+ KSP), WorkManager,
      Hilt (+ HiltWorker + KSP), DataStore, Navigation-Compose, Coil
- [x] 1.2 Enable KSP and Hilt Gradle plugins; add `implementation(project(":gamification"))`
- [x] 1.3 Read `steam.apiKey` / `steam.steamId` from `local.properties` into
      `BuildConfig` fields (with empty-string fallback); document keys in README
- [x] 1.4 Add `INTERNET` permission; confirm `local.properties` stays git-ignored
- [x] 1.5 Application class annotated with `@HiltAndroidApp`

## 2. Steam remote layer (steam-sync)
- [x] 2.1 DTOs for `GetOwnedGames` (appid, name, img_icon_url, playtime_forever,
      playtime_2weeks) and `GetSteamLevel`
- [x] 2.2 `SteamApi` Retrofit interface (`include_appinfo=1`, `include_played_free_games=1`)
- [x] 2.3 OkHttp/Retrofit + kotlinx.serialization Hilt module
- [x] 2.4 Map `img_icon_url` → full Steam CDN icon URL

## 3. Local persistence (steam-sync / gamification)
- [x] 3.1 Room entities: `Game`, `Session`, `DailyProgress`, `PlayerProfile`
- [x] 3.2 DAOs with `Flow` queries (library, goal games, recent sessions, profile, daily stats)
- [x] 3.3 `BacklogiumDatabase` + Hilt module
- [x] 3.4 DataStore for SteamID/settings + `RuleConfig` defaults

## 4. Session synthesis (steam-sync)
- [x] 4.1 `SessionDiffer`: per-game `playtime_forever` diff → open/extend/close sessions
- [x] 4.2 First-sync baselining (store totals, emit zero sessions)
- [x] 4.3 Close session after a no-increase poll (end = last-increase time)
- [x] 4.4 Guard decreases, disappearing games, empty/private responses
- [x] 4.5 Unit tests for differ: baseline, single increase, multi-poll session, close,
      decrease, missed-short-play

## 5. Gamification integration (consumes `:gamification`)
- [x] 5.1 Build engine inputs from Room (per-game tracked minutes, `DayInput` rows, goal targets)
- [x] 5.2 Inject current date/timezone into the engine (engine has no clock)
- [x] 5.3 On each sync + on day rollover: call `xp` / `quest` / `streak` / `goalProgress`
- [x] 5.4 Persist outputs back to Room (`PlayerProfile.totalXp/level/streaks`, `DailyProgress.questMet`)
- [x] 5.5 Integration test: seeded sessions → expected persisted XP/level/quest/streak

## 6. Sync worker (steam-sync)
- [x] 6.1 `SteamSyncWorker` (`HiltWorker`): fetch → differ → persist → rule engine
- [x] 6.2 Schedule 15-min periodic work (CONNECTED, KEEP, backoff); enqueue on app start
- [x] 6.3 "Sync now" one-time expedited request
- [x] 6.4 Persist `lastSyncAt` / `lastSyncError` to `PlayerProfile`

## 7. Repositories (all capabilities)
- [x] 7.1 `GameRepository`, `SessionRepository`, `ProfileRepository` exposing Flows
- [x] 7.2 Goal tag / target set / untag operations

## 8. UI (app-ui)
- [x] 8.1 Navigation scaffold (bottom nav: Home / Library / History)
- [x] 8.2 Home: level+XP bar, today's quest, streak, "Sync now", error banner
- [x] 8.3 Library: goal games w/ progress bars + backlog; tag/target/untag dialog
- [x] 8.4 History: recent sessions list + per-day stats
- [x] 8.5 ViewModels backed by repository Flows (no logic in Composables)
- [x] 8.6 "Steam not configured" / "profile may be private" empty states

## 9. Validation (README Phase 3–4 checkpoints)
- [ ] 9.1 Install on device; verify baseline creates no fake sessions
- [ ] 9.2 Play a real game; confirm a session appears after the next poll
- [ ] 9.3 Track a few days; confirm streak, quest, and goal progress behave
- [ ] 9.4 Airplane mode: app still renders last synced state
