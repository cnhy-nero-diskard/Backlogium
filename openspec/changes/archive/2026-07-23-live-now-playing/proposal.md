## Why

The app only ever computes playtime *retrospectively* — it diffs Steam total-playtime
snapshots into past sessions. It never asks Steam "what are you playing right now," so
there is no live feedback even while a game is actively running. Steam exposes current
game via `GetPlayerSummaries` (`gameextrainfo`), which the app has never fetched.

## What Changes

- **Fetch current-game state** from Steam via `ISteamUser/GetPlayerSummaries/v2/`,
  reading `gameid`/`gameextrainfo` to determine whether the player is in-game and which
  game.
- **Foreground live poll** (~30s cadence) that runs only while the app is foregrounded
  and the Home screen is observing — independent of WorkManager (whose periodic floor is
  15 minutes). Background playtime sync is unchanged and stays on its 15-minute schedule.
- **"Now playing" indicator on Home**: a banner showing the currently running game (name
  + icon when resolvable), hidden when the player is not in-game.
- The now-playing state is **ephemeral/in-memory** (not persisted to Room), since it is a
  transient live signal.

## Capabilities

### New Capabilities
- `live-status`: fetching and exposing the player's current in-game state (in-game or
  not, and which game), on a foreground polling cadence.

### Modified Capabilities
- `app-ui`: the Home screen gains a "Now playing" indicator driven by live status.

## Impact

- **Affected code:**
  - `data/remote/SteamApi.kt` — add `getPlayerSummaries(...)`; new DTOs under
    `data/remote/dto/` for the player-summary response.
  - New `data/repo/LiveStatusRepository.kt` (or similar) exposing a `Flow`/`StateFlow`
    of now-playing state, polling on a foreground-scoped cadence.
  - `ui/home/HomeViewModel.kt` + `ui/home/HomeScreen.kt` — combine now-playing into
    `HomeUiState`; render the banner.
  - Steam icon resolution reused from `SteamIconMapper` / owned-games data where possible.
- **Dependencies:** none new; reuses Retrofit/Steam Web API and the existing API key.
- **Out of scope / unchanged:** background periodic sync cadence (stays 15-min), session
  synthesis, and any persistence of live state.
