## Why

The game detail screen (`enhance-game-detail`) already tells a player everything about their own
relationship to a game — playtime, HowLongToBeat lengths, achievement completion, XP. It says
nothing about the game as a live thing other people are playing right now. Steam exposes exactly
that number — current concurrent players — through a public, key-less endpoint the app doesn't
call yet. Showing it turns the detail screen from a purely personal ledger into something that
also answers "is this game still active?"

## What Changes

- A new one-shot fetch of Steam's current concurrent-player count for the game being viewed,
  issued when the game detail screen opens.
- The count is shown on the existing summary card (near playtime/completion) when available, and
  simply omitted — never a zero or a dash — when Steam has no count for that app (delisted,
  invalid, or the request fails).
- The count is never persisted: it is a live fact, fetched fresh per visit and held only in
  screen state, the same posture the app already takes with the player's own live presence.

## Capabilities

### New Capabilities
- `steam-player-count`: fetching a single Steam app's current concurrent-player count via
  `ISteamUserStats/GetNumberOfCurrentPlayers`, and the fallback-to-absent behavior when Steam
  has no count to give.

### Modified Capabilities
- `app-ui`: the game detail screen's summary gains a current-player-count line.

## Impact

- **Affected code (new):** `SteamApi.getNumberOfCurrentPlayers`, a `CurrentPlayersResponse` DTO,
  a `GameRepository.currentPlayerCount(appId)` suspend function.
- **Affected code (modified):** `GameDetailViewModel` (fetch on load, add `activePlayers: Int?` to
  `GameSummaryUi`), `GameDetailScreen`'s `GameSummarySection`.
- **No new persistence.** No Room column, no migration — this is intentionally never stored.
- **No credentials dependency.** `GetNumberOfCurrentPlayers` takes no API key, unlike every other
  `SteamApi` call in this codebase, so the fetch cannot fail for a reason tied to the user's own
  Steam credentials.
- **Out of scope:** the Library screen/list, any cross-game leaderboard or ranking, and any
  historical/trend view of player count. This change touches the detail screen only.
