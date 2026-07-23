## Context

The Steam integration is purely retrospective: `SteamSyncWorker` calls
`GetOwnedGames` (+ `GetSteamLevel`), diffs total-playtime snapshots into `Session`
rows, and recomputes gamification. `SteamApi` (Retrofit) currently exposes only those
two endpoints. `PlayerProfile` is a single persisted row of aggregates.

Steam's `ISteamUser/GetPlayerSummaries/v2/` returns, per steamid, `gameid` and
`gameextrainfo` (the running game's display name) when the player is in-game — the
signal needed for a live "Now playing" indicator. It is never fetched today.

Android's `PeriodicWorkRequest` is floored at 15 minutes, so a ~30s refresh cannot use
WorkManager; it must be an in-app, foreground-scoped loop.

## Goals / Non-Goals

**Goals:**
- Fetch current in-game state from Steam and expose it as a live, in-memory state.
- Refresh it ~every 30s while Home is foregrounded/observing, and stop otherwise.
- Show a "Now playing" banner on Home; hide it when not in-game.
- Leave background playtime sync (15-min WorkManager) completely untouched.

**Non-Goals:**
- Persisting now-playing state (it is ephemeral by nature).
- Changing the periodic sync cadence or session synthesis.
- Rich presence beyond the running game (e.g. server/lobby details).
- Playtime attribution from live state (still comes from the diffing sync).

## Decisions

- **New endpoint + DTOs.** Add `getPlayerSummaries(key, steamids)` to `SteamApi`
  returning a `PlayerSummariesResponse` (`response.players[]` with `steamid`, `gameid`,
  `gameextrainfo`, `personastate`). Note the query param is `steamids` (plural, CSV);
  we pass the single configured id.

- **Foreground-scoped poll via a cold flow + `WhileSubscribed`.** Model now-playing as
  `flow { while (true) { emit(fetch()); delay(30_000) } }` exposed from a
  `LiveStatusRepository`, and let `HomeViewModel` fold it into `HomeUiState` through
  `stateIn(SharingStarted.WhileSubscribed(5_000))` — the same pattern the ViewModel
  already uses. Subscription lifetime *is* the poll lifetime: it ticks only while Home
  collects and stops shortly after it stops, with no manual lifecycle wiring. This is
  preferred over a Service or a manually managed coroutine because it can't leak and is
  automatically foreground-scoped.

- **In-memory only.** Now-playing is not written to Room; no schema/migration change.
  On failure, keep the last emitted value (or emit an "unknown"/not-in-game fallback)
  rather than throwing, so a transient error doesn't clear the banner abruptly.

- **Icon resolution reuses owned-games data.** `gameid` from the summary is matched
  against the already-synced `Game` rows (and `SteamIconMapper`) to show an icon;
  fall back to name-only when the game isn't in the owned set.

- **Banner placement.** Render a compact "Now playing: <game>" element at the top of
  Home, conditionally composed only when in-game, so it adds no layout when hidden
  (mirroring the existing celebration-animation "renders nothing while idle" approach).

## Risks / Trade-offs

- **Rate limits / battery.** A 30s foreground poll is well within Steam Web API limits
  and only runs while the screen is open; background is unaffected. Acceptable.
- **Private profiles.** `GetPlayerSummaries` returns no game info for private profiles;
  the indicator simply stays hidden — no error surfaced to the user.
- **`gameextrainfo` may be absent for non-Steam / unknown games** even when `gameid`
  is set; handle by showing whatever identity is available (name or id) and an icon
  only when resolvable.
- **Poll/HTTP overlap on slow networks** — a fetch slower than 30s could stack; guard by
  sequencing (await each fetch before the next `delay`), which the `while` loop already
  does.
