## ADDED Requirements

### Requirement: The end of an observed session is actionable
When presence observation determines that a game which was running is no longer running, the system
SHALL make that transition — including the identity of the game that stopped — available to work
that acts on it. Publishing the transition SHALL NOT cause presence observation to perform network
or library work of its own, and SHALL NOT persist the now-playing state.

#### Scenario: Stopped game is identified
- **WHEN** an observation reports that the player is no longer in a game they were previously
  observed in
- **THEN** the transition is published carrying the identity of the game that stopped

#### Scenario: Presence remains transient
- **WHEN** a session-end transition is published
- **THEN** the now-playing state is still not persisted, and the recorded live session state is
  cleared as it is today

#### Scenario: Publishing performs no work
- **WHEN** a session-end transition is published
- **THEN** presence observation issues no additional request and performs no library-scale work as
  part of publishing it

#### Scenario: Observer lifecycle does not fabricate an end
- **WHEN** presence observation stops because its host was killed, timed out, or the process died
- **THEN** no session-end transition is published, since the recorded session state is retained
  rather than cleared

#### Scenario: Presence state change with the game still running
- **WHEN** the player's Steam presence changes between online, away, snooze, or offline while the
  same game is still reported as running
- **THEN** no session-end transition is published

#### Scenario: Consumer failure does not affect presence
- **WHEN** work acting on a published session-end transition fails
- **THEN** presence observation is unaffected and continues on its existing cadence
