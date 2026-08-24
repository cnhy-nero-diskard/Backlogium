## MODIFIED Requirements

### Requirement: Session synthesis by playtime diffing
The system SHALL synthesize play sessions by comparing each game's `playtime_forever`
against the previously stored value, since the Steam Web API does not expose session
or "currently playing" data. This mechanism SHALL apply only to games for which Steam reports
playtime — those in the player's own library — so that a game whose sessions are derived from
observed presence is never also diffed.

#### Scenario: Playtime increases
- **WHEN** a game's `playtime_forever` is greater than its stored value
- **THEN** an open session for that game is created if none exists, extended by the delta minutes, and its last-increase timestamp updated

#### Scenario: Playtime unchanged
- **WHEN** a game with an open session shows no increase on the next poll
- **THEN** the session is closed with its end time set to the last-increase timestamp

#### Scenario: Playtime decreases
- **WHEN** a game's `playtime_forever` is less than its stored value (e.g. family sharing or refund)
- **THEN** no session is emitted and the decrease does not produce negative playtime

#### Scenario: A game without Steam-reported playtime is not diffed
- **WHEN** a tracked game is absent from the player's Steam library
- **THEN** it is excluded from playtime diffing entirely, and no session for it is created or
  closed by this mechanism
