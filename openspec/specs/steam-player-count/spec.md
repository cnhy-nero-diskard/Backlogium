# steam-player-count

## Purpose

Defines how the app looks up a game's current Steam concurrent-player count for
display on the game detail screen: an unauthenticated lookup, polled every 30
seconds while the screen is open, that is never persisted.

## Requirements

### Requirement: Current player count lookup
The system SHALL fetch a game's current concurrent-player count from Steam's
`ISteamUserStats/GetNumberOfCurrentPlayers` endpoint using only the game's Steam app id, with no
API key or SteamID required. The lookup SHALL be repeated every 30 seconds for as long as the
game detail screen for that game remains open, SHALL stop once the screen is left, and SHALL NOT
be persisted.

#### Scenario: Count available
- **WHEN** a lookup for a game's app id succeeds and Steam reports a player count
- **THEN** that count is made available to the caller

#### Scenario: Count unavailable
- **WHEN** a lookup fails (network error, non-success result, or a missing player count in an
  otherwise successful response)
- **THEN** the lookup resolves to no count rather than raising an error or a placeholder value such
  as zero

#### Scenario: Polling continues while the screen is open
- **WHEN** the game detail screen for a game remains open
- **THEN** the system repeats the lookup for that game every 30 seconds

#### Scenario: Polling stops when the screen is left
- **WHEN** the player navigates away from the game detail screen
- **THEN** no further lookups are performed for that game

#### Scenario: Not persisted
- **WHEN** a lookup completes, successfully or not
- **THEN** the result is not written to local storage, so a subsequent app launch has no stored
  value to read
