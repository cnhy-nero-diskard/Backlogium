## 1. Steam current-game endpoint

- [ ] 1.1 Add DTOs under `data/remote/dto/` for the `GetPlayerSummaries` response (`response.players[]` with `steamid`, `gameid`, `gameextrainfo`, `personastate`)
- [ ] 1.2 Add `getPlayerSummaries(key, steamids)` to `data/remote/SteamApi.kt` targeting `ISteamUser/GetPlayerSummaries/v2/` (note: `steamids` query param, CSV)

## 2. Live-status repository + polling

- [ ] 2.1 Add a `NowPlaying` model (in-game with game identity vs not-in-game) and a `LiveStatusRepository` exposing it as a cold `Flow` that fetches then `delay(30_000)` in a loop
- [ ] 2.2 Resolve the running game's icon by matching `gameid` against synced `Game` rows / `SteamIconMapper`; fall back to name-only when unavailable
- [ ] 2.3 Handle fetch failures gracefully (retain last value or emit not-in-game/unknown; never throw out of the flow) and no-op cleanly for unconfigured/private profiles

## 3. Home "Now playing" indicator

- [ ] 3.1 Fold now-playing into `HomeViewModel.uiState` via `stateIn(WhileSubscribed(5_000))` so polling is foreground/observation-scoped
- [ ] 3.2 Add now-playing fields to `HomeUiState`
- [ ] 3.3 In `ui/home/HomeScreen.kt`, render a compact "Now playing: <game>" banner (name + icon when resolvable) only when in-game, adding no layout when hidden
- [ ] 3.4 Confirm the background periodic sync (15-min WorkManager) is untouched

## 4. Verification

- [ ] 4.1 Launch a game on Steam and confirm the banner appears within ~30s and clears within ~30s of quitting the game; confirm it stays hidden for a private/not-in-game profile
- [ ] 4.2 Confirm polling stops when the app is backgrounded (no requests while unobserved)
- [ ] 4.3 Run `./gradlew assembleDebug` and existing unit tests; run `openspec validate live-now-playing`
