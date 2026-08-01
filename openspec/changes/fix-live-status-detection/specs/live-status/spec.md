## MODIFIED Requirements

### Requirement: Live polling cadence and ownership
The system SHALL refresh the current in-game state on a short cadence (approximately every 30
seconds) while the player is in a game, owned by the background presence observer rather than by
screen observation. Screens SHALL observe shared live state without being responsible for keeping
the refresh alive. This live refresh SHALL be independent of the periodic background playtime
sync, whose cadence is unchanged.

#### Scenario: Refreshing while in a game
- **WHEN** the player is in a game and presence observation is active
- **THEN** the current in-game state is refreshed approximately every 30 seconds

#### Scenario: Screens do not own the cadence
- **WHEN** a screen stops observing live status but the player is still in a game
- **THEN** the refresh continues, and live state remains available to any other observer

#### Scenario: Background sync unaffected
- **WHEN** the live refresh is running or stopped
- **THEN** the periodic background playtime sync continues on its own 15-minute schedule,
  unchanged

## ADDED Requirements

### Requirement: Presence resolved before library work
A sync SHALL resolve and act on the player's current in-game state before performing any
library-scale work in that run, so presence latency is bounded by a single request rather than by
the duration of the surrounding sync.

#### Scenario: Presence acted on before achievement fetching
- **WHEN** a sync retrieves a player summary reporting a running game
- **THEN** presence observation begins before that run fetches achievement data for library games

#### Scenario: Presence unaffected by library-side conditions
- **WHEN** a sync cannot proceed with library work — for example the owned-games list is empty
  because it is private
- **THEN** the player's current in-game state is still resolved and acted on in that run

#### Scenario: Presence unaffected by later sync failure
- **WHEN** presence has been resolved and a later stage of the same sync run fails
- **THEN** the resolved presence state is retained and observation continues

### Requirement: Presence re-checked on app foreground
The system SHALL re-check the player's current in-game state each time the app enters the
foreground, so a game started while the app was open or backgrounded is detected on return
without requiring a restart.

#### Scenario: Game started while the app is backgrounded
- **WHEN** the player launches a game while the app is backgrounded, then returns to the app
- **THEN** the current in-game state is re-checked and the running game is reflected

#### Scenario: Game started while the app is open
- **WHEN** the player launches a game while the app is open and later returns to it
- **THEN** the running game is reflected without the app being restarted

#### Scenario: Repeated foregrounding
- **WHEN** the app is foregrounded again while already observing a running game
- **THEN** the re-check is harmless and does not restart the recorded session start time

### Requirement: Session state outlives the observer
Stopping presence observation for lifecycle reasons SHALL NOT clear the recorded live session.
Recorded session state SHALL be cleared only when an observation reports that the player is no
longer in a game.

#### Scenario: Observer stopped by the system
- **WHEN** presence observation stops because its host was killed, timed out, or the process died
- **THEN** the recorded session start time is retained and elapsed time continues from it once
  observation resumes

#### Scenario: Game genuinely ended
- **WHEN** an observation reports that the player is no longer in a game
- **THEN** the recorded session state is cleared

#### Scenario: Failed observation does not clear state
- **WHEN** an observation attempt fails with a network or API error
- **THEN** the recorded session state is retained and is not treated as the game having ended

### Requirement: Presence rehydrated on cold start
When the app starts and a live session is already recorded, the system SHALL present the
in-game state from that recorded session immediately, before any network result arrives, and
SHALL reconcile it with the first successful observation.

#### Scenario: Recorded session present at start
- **WHEN** the app starts and a live session for a game is recorded
- **THEN** the in-game state reflects that game immediately, with elapsed time measured from the
  recorded start

#### Scenario: Reconciled with the first observation
- **WHEN** the first successful observation after start reports the player is no longer in a game,
  or is in a different game
- **THEN** the rehydrated state is replaced by the observed state and the recorded session is
  updated accordingly

#### Scenario: No recorded session
- **WHEN** the app starts with no recorded live session
- **THEN** no in-game state is presented until an observation reports one
