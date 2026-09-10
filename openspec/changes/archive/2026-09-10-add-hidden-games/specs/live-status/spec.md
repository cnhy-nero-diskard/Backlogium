## MODIFIED Requirements

### Requirement: Current in-game state
The system SHALL determine the player's current in-game state from Steam, distinguishing
"in-game" (with the running game's identity) from "not in-game", using
`ISteamUser/GetPlayerSummaries` (`gameid`/`gameextrainfo`). Where the reported running game is
hidden, the state SHALL resolve to "not in-game", so that a single resolution point governs every
surface rather than each filtering hidden games separately. The now-playing state is a
transient live signal and SHALL NOT be persisted.

#### Scenario: Player is in a game
- **WHEN** the live-status poll runs and Steam reports the player is in a game
- **THEN** the now-playing state resolves to in-game with that game's identity (name, and
  icon/app id when available)

#### Scenario: Player is not in a game
- **WHEN** the live-status poll runs and Steam reports no running game
- **THEN** the now-playing state resolves to not in-game

#### Scenario: Player is in a hidden game
- **WHEN** the live-status poll runs and Steam reports the player is in a game that is hidden
- **THEN** the now-playing state resolves to not in-game, and no surface derived from it names or
  depicts that game

#### Scenario: Live fetch fails
- **WHEN** the live-status poll fails (network or API error)
- **THEN** the failure does not crash the app or clobber other synced data; the last
  known now-playing state is retained or treated as unknown until the next successful poll
