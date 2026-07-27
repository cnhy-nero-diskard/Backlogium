# Steam profile header

## Why

The app knows *whose* library it is showing but never says so. `CredentialsRepository` holds a
SteamID64, and Home surfaces it as raw digits on the Steam account card — an identifier, not an
identity. Steam itself leads with a persona showcase (avatar, name, state), which is what makes
a client feel like *your* account rather than a generic dashboard.

`ISteamUser/GetPlayerSummaries` is already called every 30 seconds by
`LiveStatusRepository`, and its response already carries `personaname`, `avatarfull`, and
`personastate` — the app simply discards them, deserializing only `gameid`/`gameextrainfo`.
The identity data is free; only the plumbing is missing.

## What Changes

- A slim, always-present **profile header** at the top of the app shell: avatar, persona name,
  and online/in-game state, visible on every top-level screen.
- `PlayerSummaryDto` grows the identity fields Steam already returns (`personaname`,
  `avatarfull`, `personastate`), so no new network call is introduced.
- Persona name and avatar URL are **persisted** on `PlayerProfile`, so the header renders from
  local state on a cold offline launch (the existing `app-ui` offline requirement) rather than
  waiting on a poll.
- The header is **hidden while unconfigured**, since Home already replaces itself with the
  full-screen onboarding takeover in that state.

## Capabilities

### Modified Capabilities
- `app-ui`: the app shell gains a persistent profile header. Additive — no existing screen's
  behavior changes.
- `steam-sync`: the sync persists the player's persona name and avatar alongside the existing
  profile aggregates.

## Impact

- **Affected code (new):** a profile-header composable in `ui/components`, plus its state on
  the shell.
- **Affected code (modified):** `PlayerSummaryDto` (three additive fields);
  `PlayerProfile` entity gains `personaName`/`avatarUrl` (additive migration);
  `SteamSyncWorker` persists them; `BacklogiumAppRoot` gains a `topBar`;
  `LiveStatusRepository` exposes persona state so the header can reflect in-game live.
- **No new network calls.** Both the periodic sync and the live poll already hit endpoints that
  return this data.

## Non-goals

- **Showing the Steam level in the header.** `GetSteamLevel` is already fetched and stored, but
  Home displays a prominent "Level N" card for the app's *own* XP level; a second, unrelated
  level number at the top of the same screen reads as a contradiction. Steam identity only.
- **Profile editing or a profile screen.** The header is a showcase, not a destination. The
  existing Steam account card on Home remains the place to change credentials.
- **Friends, showcases, or badges.** Steam's mini-profile has much more in it; this change
  takes only the identity strip.
- **Replacing the Home Steam account card.** It carries the masked API key and the edit action,
  which are settings concerns, not identity.
