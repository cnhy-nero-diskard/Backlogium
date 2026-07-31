## 1. Network surface

- [x] 1.1 Add `CurrentPlayersResponse`/`CurrentPlayersResult` DTOs to `data/remote/dto` (`player_count: Int?`, `result: Int`), mirroring the shape of existing DTOs like `PlayerSummariesDto.kt`
- [x] 1.2 `SteamApi`: add `getNumberOfCurrentPlayers(appId: Long): CurrentPlayersResponse` hitting `ISteamUserStats/GetNumberOfCurrentPlayers/v1/` with only the `appid` query param — no `key`
- [x] 1.3 Confirm (manual check against the live endpoint is enough) that an invalid/delisted appid returns `result != 1` with no `player_count`, and that this does not throw during deserialization

## 2. Repository

- [x] 2.1 `GameRepository`: add `suspend fun currentPlayerCount(appId: Long): Int?` that calls `SteamApi.getNumberOfCurrentPlayers` and returns `null` on any failure (network exception, `result != 1`, or missing `player_count`)
- [x] 2.2 No new persistence: confirm nothing here writes to `GameDao` or any Room entity

## 3. Game detail screen

- [x] 3.1 `GameSummaryUi`: add `activePlayers: Int?`
- [x] 3.2 `GameDetailViewModel`: fetch `currentPlayerCount(appId)` once when the screen loads (not part of the existing `combine` of local Flows) and fold the result into `GameSummaryUi` as it resolves, without delaying the rest of the state
- [x] 3.3 `GameDetailScreen`/`GameSummarySection`: render the count (e.g. "1,206,380 playing now") near the existing playtime/completion lines when `activePlayers != null`; render nothing when it is `null` — no zero, no dash, no spinner left hanging
- [x] 3.4 Verify the rest of the summary (art, playtime, HLTB, completion, XP) and the achievement list render immediately regardless of whether the player-count fetch has resolved, failed, or is still in flight

## 4. Docs & specs

- [x] 4.1 Update `docs/ui-screens-descriptor.md` to mention the current-player-count line on the game detail screen
- [x] 4.2 Verify the `app-ui` and `steam-player-count` spec deltas match the built behavior
