## MODIFIED Requirements

### Requirement: Session mechanism is determined by source
Sessions for a game with Steam-reported playtime SHALL be synthesized by diffing that playtime.
Sessions for a game without Steam-reported playtime SHALL be derived from observed presence. No
game SHALL be subject to both mechanisms.

Observed presence MAY reach the presence mechanism from more than one observer — the on-device
poll, or presence recorded elsewhere and read back. The number of observers does not change the
number of mechanisms: every observer feeds the same derivation, which remains the single author of
a presence-derived session. A game SHALL NOT be subject to both mechanisms regardless of how many
observers reported it.

#### Scenario: An owned game
- **WHEN** sessions are synthesized for an owned game
- **THEN** they come from playtime diffing, and no presence-derived session is created for it, from
  any observer

#### Scenario: A family-shared game
- **WHEN** a family-shared game is observed in presence over successive observations
- **THEN** an open session is derived and extended, and closed once it is no longer observed

#### Scenario: A derived session is an ordinary session
- **WHEN** a session has been derived from presence
- **THEN** it participates in XP, quests, streaks, history, and analytics identically to a
  playtime-derived session

#### Scenario: No overlap
- **WHEN** any game's sessions are examined
- **THEN** they originate from exactly one mechanism

#### Scenario: Two observers, one mechanism
- **WHEN** the same stretch of play for a family-shared game is reported by both the on-device
  observer and a record read back from elsewhere
- **THEN** one mechanism derives it, and the credited time reflects that play once

### Requirement: Tracked time for a shared game is disclosed as observed, not total
Where playtime for a family-shared game is presented, the system SHALL convey that it reflects
only play the app observed — plus any manual estimate the player has set — and SHALL NOT present
it as the player's complete or Steam-verified time in that game.

Observation may include presence recorded while the app was not running and read back later. That
widens what "observed" covers; it does not change what the figure claims. Time that no observer
confirmed SHALL NOT be included on the strength of surrounding observations.

#### Scenario: Viewing a shared game's playtime
- **WHEN** a family-shared game's tracked playtime is shown
- **THEN** it is presented as the time the app observed rather than as a Steam total

#### Scenario: Play while unobserved
- **WHEN** a family-shared game is played while no observer — on-device or otherwise — recorded it
- **THEN** no session is derived for that play, and the game's tracked time is unchanged

#### Scenario: Play observed only away from the device
- **WHEN** a family-shared game is played while the app is not running, and presence recorded
  elsewhere covers it
- **THEN** a session is derived from that record, and the disclosure still presents the figure as
  observed rather than as a Steam total

#### Scenario: The remedy is offered
- **WHEN** the disclosure is shown and the observers that would improve coverage are not all enabled
- **THEN** the player is pointed at the settings that would improve it

#### Scenario: A manual estimate is included but still not claimed as verified
- **WHEN** a family-shared game has a manual playtime estimate set
- **THEN** the presented time includes it, and the disclosure still does not claim the figure is a
  Steam-verified total
