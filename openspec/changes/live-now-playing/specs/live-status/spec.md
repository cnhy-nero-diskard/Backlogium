## ADDED Requirements

### Requirement: Current in-game state
The system SHALL determine the player's current in-game state from Steam, distinguishing
"in-game" (with the running game's identity) from "not in-game", using
`ISteamUser/GetPlayerSummaries` (`gameid`/`gameextrainfo`). The now-playing state is a
transient live signal and SHALL NOT be persisted.

#### Scenario: Player is in a game
- **WHEN** the live-status poll runs and Steam reports the player is in a game
- **THEN** the now-playing state resolves to in-game with that game's identity (name, and
  icon/app id when available)

#### Scenario: Player is not in a game
- **WHEN** the live-status poll runs and Steam reports no running game
- **THEN** the now-playing state resolves to not in-game

#### Scenario: Live fetch fails
- **WHEN** the live-status poll fails (network or API error)
- **THEN** the failure does not crash the app or clobber other synced data; the last
  known now-playing state is retained or treated as unknown until the next successful poll

### Requirement: Foreground live polling cadence
The system SHALL refresh the current in-game state on a short foreground cadence
(approximately every 30 seconds) that runs only while the app is foregrounded and the
consuming screen is active, and SHALL stop polling when it is not. This live poll SHALL be
independent of the periodic background playtime sync, whose cadence is unchanged.

#### Scenario: Polling while observed
- **WHEN** the Home screen is foregrounded and observing live status
- **THEN** the current in-game state is refreshed approximately every 30 seconds

#### Scenario: Polling stops when unobserved
- **WHEN** no screen is observing live status (e.g. the app is backgrounded)
- **THEN** the live poll stops issuing requests

#### Scenario: Background sync unaffected
- **WHEN** the live poll is running or stopped
- **THEN** the periodic background playtime sync continues on its own 15-minute schedule,
  unchanged
