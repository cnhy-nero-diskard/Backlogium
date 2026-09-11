## Purpose

Defines how the app looks up a game's current Steam concurrent-player count: an unauthenticated
lookup that is never persisted. The game detail screen polls it every 30 seconds while open; other
callers — a finalized gap plan's multiplayer members — issue one bounded, one-shot lookup under
their own lifecycle. Polling is a property of the detail screen, not of every lookup.

## MODIFIED Requirements

### Requirement: Current player count lookup
The system SHALL fetch a game's current concurrent-player count from Steam's
`ISteamUserStats/GetNumberOfCurrentPlayers` endpoint using only the game's Steam app id, with no
API key or SteamID required. A game-detail lookup SHALL be repeated every 30 seconds for as long as
the game detail screen for that game remains open, SHALL stop once the screen is left, and SHALL NOT
be persisted. The 30-second repetition is a property of the game-detail lifecycle specifically, not
of every lookup: another caller may issue a one-shot lookup with a lifecycle of its own, and SHALL
NOT inherit the polling obligation.

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

#### Scenario: A non-detail caller does not poll
- **WHEN** a caller outside the game detail screen issues a current-player lookup
- **THEN** that lookup runs once under its own lifecycle and is not repeated every 30 seconds

#### Scenario: Not persisted
- **WHEN** a lookup completes, successfully or not
- **THEN** the result is not written to local storage, so a subsequent app launch has no stored
  value to read

## ADDED Requirements

### Requirement: Gap-plan pick player-count lookup
The system SHALL permit one on-demand current-player lookup per distinct eligible multiplayer game
already present in a finalized gap-plan result, up to three games per generation — the result offers
one pick per intensity tier and cannot contain more. The lookups SHALL require no API key, SHALL be
concurrency-bounded and time-bounded rather than sent as an unthrottled burst, SHALL stop when the
result is abandoned or superseded, and SHALL NOT be persisted or polled repeatedly. They SHALL NOT
influence which games the result offers. The existing game-detail polling lifecycle SHALL remain
unchanged.

#### Scenario: Bounded by the picks
- **WHEN** a generation's three picks are finalized
- **THEN** only the multiplayer games among them receive a current-player lookup, and never more than three app ids

#### Scenario: Only multiplayer picks are looked up
- **WHEN** a generation's picks include single-player games
- **THEN** no lookup is issued for those, so a wholly single-player result issues none at all

#### Scenario: Generation is superseded
- **WHEN** the player rerolls or leaves while lookups remain in flight
- **THEN** the abandoned lookups publish no result into the replacement or closed suggestion state

#### Scenario: Enrichment window elapses
- **WHEN** the bounded enrichment window ends before every lookup has answered
- **THEN** the picks are already complete and usable, and late results are discarded rather than applied

#### Scenario: Count is not persisted
- **WHEN** a lookup succeeds
- **THEN** its count exists only for the current suggestion lifecycle and is absent after that lifecycle ends

#### Scenario: Detail polling remains independent
- **WHEN** the same game is later opened on the game-detail screen
- **THEN** its established 30-second detail polling begins with a fresh lookup rather than consuming a stored suggestion count
