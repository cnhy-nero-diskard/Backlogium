# steam-player-count

## Purpose

Defines how the app looks up a game's current Steam concurrent-player count for
display on the game detail screen: a per-visit, unauthenticated lookup that is
never persisted.

## Requirements

### Requirement: Current player count lookup
The system SHALL fetch a game's current concurrent-player count from Steam's
`ISteamUserStats/GetNumberOfCurrentPlayers` endpoint using only the game's Steam app id, with no
API key or SteamID required. The lookup SHALL be performed once per game detail screen visit and
SHALL NOT be persisted.

#### Scenario: Count available
- **WHEN** the lookup for a game's app id succeeds and Steam reports a player count
- **THEN** that count is made available to the caller

#### Scenario: Count unavailable
- **WHEN** the lookup fails (network error, non-success result, or a missing player count in an
  otherwise successful response)
- **THEN** the lookup resolves to no count rather than raising an error or a placeholder value such
  as zero

#### Scenario: Not persisted
- **WHEN** a lookup completes, successfully or not
- **THEN** the result is not written to local storage, so a subsequent app launch has no stored
  value to read
