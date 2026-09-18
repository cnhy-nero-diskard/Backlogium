## ADDED Requirements

### Requirement: The watch runs only while presence observes a running game
The system SHALL poll the currently running game's achievements only while presence observation is
active and reports that the player is in a game. The watch SHALL begin when presence begins reporting
a game, SHALL target only that game, and SHALL stop when presence stops reporting it, for any reason.
The watch SHALL NOT establish a schedule of its own and SHALL NOT extend the lifetime of presence
observation.

#### Scenario: Watch begins with presence
- **WHEN** presence observation reports that the player is in a game
- **THEN** the watch begins polling that game's achievements

#### Scenario: Watch targets one game
- **WHEN** the watch is running
- **THEN** it polls only the running game, and no other library game

#### Scenario: Game ends
- **WHEN** presence reports the player is no longer in a game
- **THEN** the watch stops and issues no further requests

#### Scenario: Game changes
- **WHEN** presence reports a different running game
- **THEN** the watch stops watching the previous game and begins a new watch session for the new one

#### Scenario: Presence stops for a lifecycle reason
- **WHEN** presence observation stops because its host was killed, timed out, or the platform ended
  it
- **THEN** the watch stops with it, and does not continue, restart itself, or keep the host alive

#### Scenario: Not in a game
- **WHEN** the player is not in a game
- **THEN** the watch issues no requests at all

#### Scenario: Watch never extends presence
- **WHEN** the watch has work outstanding and presence observation is ending
- **THEN** presence ends on its own schedule and the outstanding work is abandoned

### Requirement: Watch cadence backs off and resets on an unlock
The watch SHALL poll on an interval that begins at a short floor, increases across consecutive
observations that find no new unlock up to a defined ceiling, and returns to the floor whenever an
unlock is observed. The floor SHALL be no shorter than the presence cadence, and the ceiling SHALL
bound the cost of a long session in which nothing happens.

#### Scenario: Floor at the start of a watch session
- **WHEN** a watch session begins
- **THEN** its first interval is the floor

#### Scenario: Backing off while nothing happens
- **WHEN** consecutive observations find no new unlock
- **THEN** the interval increases toward the ceiling and does not exceed it

#### Scenario: Reset on an unlock
- **WHEN** an observation finds one or more new unlocks
- **THEN** the interval returns to the floor

#### Scenario: A long idle session is bounded
- **WHEN** a game runs for many hours with no unlock
- **THEN** the number of requests issued is bounded by the ceiling rather than by the floor

#### Scenario: Cadence never exceeds presence's own
- **WHEN** the watch is running
- **THEN** it does not poll more often than presence observation does

### Requirement: The first observation of a watch session is a baseline
The first observation in a watch session SHALL establish that session's baseline: it SHALL store what
it finds and SHALL produce no unlock event, regardless of how much of what it finds is absent from
stored state. Subsequent observations in the same watch session SHALL be compared against the
previous observation.

#### Scenario: Stale stored data does not produce events
- **WHEN** a watch session begins for a game whose stored achievement data is far behind Steam's
- **THEN** the difference is stored and no unlock event is produced

#### Scenario: No stored data at all
- **WHEN** a watch session begins for a game with no stored achievement data
- **THEN** the observed state is stored and no unlock event is produced

#### Scenario: Unlock after the baseline
- **WHEN** an observation after the first in a watch session finds an achievement unlocked that the
  previous observation showed locked
- **THEN** an unlock event is produced

#### Scenario: A new watch session re-baselines
- **WHEN** the player stops a game and later starts it again
- **THEN** the new watch session's first observation is a baseline and produces no event

#### Scenario: The baseline still stores correctly
- **WHEN** a baseline observation finds unlocks absent from storage
- **THEN** they are stored with their rarity snapshots, so XP is correct even though nothing was
  announced

### Requirement: A newly observed unlock is never stored without its rarity snapshot
Before storing an achievement as newly unlocked, the system SHALL obtain that game's global unlock
percentages, so the achievement's rarity snapshot is captured at first observation. Where the global
percentages cannot be obtained, the system SHALL store nothing for that observation rather than store
an unlocked achievement without a snapshot.

#### Scenario: Globals fetched before writing
- **WHEN** an observation finds an achievement newly unlocked
- **THEN** that game's global unlock percentages are obtained before the unlocked state is written

#### Scenario: Globals unavailable
- **WHEN** the global unlock percentages cannot be obtained for an observation that found a new
  unlock
- **THEN** nothing is written for that observation, and no unlock event is produced

#### Scenario: A dropped observation is recoverable
- **WHEN** an observation is dropped because globals were unavailable
- **THEN** the next observation, or the next sync, stores the unlock normally

#### Scenario: No globals request without an unlock
- **WHEN** an observation finds no new unlock
- **THEN** no global unlock percentages are requested

#### Scenario: Snapshot is taken from the fresh percentage
- **WHEN** an unlock is stored by the watch
- **THEN** its rarity snapshot is the freshly obtained global percentage, and is not left absent

### Requirement: The watch stores observations and derives nothing
The watch SHALL persist achievement state through the same path other refreshes use, and SHALL NOT
compute XP, levels, streaks, sessions, playtime, or any other derived value. Recomputation SHALL be
triggered through the existing path rather than performed by the watch.

#### Scenario: No derivation in the watch
- **WHEN** the watch stores an observation
- **THEN** it computes no derived value of its own

#### Scenario: Recompute happens through the existing path
- **WHEN** the watch stores a new unlock
- **THEN** the player's XP reflects it through the same recompute path a sync's unlocks flow through

#### Scenario: No session or playtime effect
- **WHEN** the watch runs for the duration of a session
- **THEN** no session is created, extended, or closed by it, and no playtime is recorded by it

### Requirement: Watch fetches are serialized with other refreshes of the same game
A watch fetch SHALL be serialized against any other refresh of the same game, so a watch observation
and a sync or reconciliation refresh cannot interleave their writes for one game.

#### Scenario: Sync during a watch
- **WHEN** a periodic sync refreshes the running game's achievements while the watch is observing it
- **THEN** the two are serialized and neither overwrites the other's partial state

#### Scenario: Watch waits rather than duplicating
- **WHEN** a refresh of the watched game is already in progress
- **THEN** the watch does not issue a duplicate concurrent refresh for it

#### Scenario: Serialization does not stall presence
- **WHEN** a watch observation is waiting on another refresh of the same game
- **THEN** presence observation continues on its own cadence, unaffected

### Requirement: The watch fails quietly
A failed watch observation SHALL NOT surface an error, SHALL NOT stop the watch, SHALL NOT affect
presence observation, and SHALL leave stored achievement data intact.

#### Scenario: Request fails
- **WHEN** a watch observation fails with a network or API error
- **THEN** no error is surfaced, stored achievement data is unchanged, and the watch continues on its
  cadence

#### Scenario: Repeated failures
- **WHEN** watch observations fail repeatedly
- **THEN** the watch continues to back off and retry within its cadence, without escalating to the
  player

#### Scenario: Failure does not affect presence
- **WHEN** a watch observation fails
- **THEN** presence observation, the now-playing state, and the ongoing presence notification are
  unaffected

#### Scenario: Game has no achievements
- **WHEN** the running game exposes no achievements
- **THEN** the watch records that and stops polling it for the remainder of the watch session, rather
  than continuing to request nothing

### Requirement: The watch can be switched off
The system SHALL provide a setting that disables the watch. When disabled, the watch SHALL issue no
requests and produce no events, and every other behaviour SHALL be unaffected.

#### Scenario: Disabled
- **WHEN** the watch is switched off and the player is in a game
- **THEN** no watch request is issued and no unlock event is produced

#### Scenario: Re-enabled mid-session
- **WHEN** the watch is switched on while the player is in a game
- **THEN** a watch session begins with a baseline observation

#### Scenario: Disabling mid-session
- **WHEN** the watch is switched off while a watch session is running
- **THEN** the session stops and no further requests are issued

#### Scenario: Everything else unaffected
- **WHEN** the watch is switched off
- **THEN** presence observation, the periodic sync, reconciliation, and the achievement data they
  store all behave exactly as they do today
