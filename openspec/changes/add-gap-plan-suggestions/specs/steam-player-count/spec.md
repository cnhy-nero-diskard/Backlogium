## ADDED Requirements

### Requirement: Gap-plan shortlist player-count lookup
The system SHALL permit one on-demand current-player lookup per distinct eligible multiplayer game
in a generated gap-plan shortlist, up to twelve games per generation. The lookups SHALL require no
API key, SHALL be spaced or concurrency-bounded rather than sent as an unthrottled burst, SHALL stop
when the result is abandoned or superseded, and SHALL NOT be persisted or polled repeatedly. The
existing game-detail polling lifecycle SHALL remain unchanged.

#### Scenario: Bounded shortlist
- **WHEN** more than twelve multiplayer games appear among local recommendation candidates
- **THEN** no more than twelve distinct app ids receive a current-player lookup for that generation

#### Scenario: Candidate appears in several variants
- **WHEN** one multiplayer game appears in more than one plan variant
- **THEN** it receives at most one current-player lookup for that generation

#### Scenario: Generation is superseded
- **WHEN** the player regenerates or leaves while shortlist lookups remain in flight
- **THEN** the abandoned lookups publish no result into the replacement or closed suggestion state

#### Scenario: Count is not persisted
- **WHEN** a shortlist lookup succeeds
- **THEN** its count exists only for the current suggestion lifecycle and is absent after that lifecycle ends

#### Scenario: Detail polling remains independent
- **WHEN** the same game is later opened on the game-detail screen
- **THEN** its established 30-second detail polling begins with a fresh lookup rather than consuming a stored suggestion count
