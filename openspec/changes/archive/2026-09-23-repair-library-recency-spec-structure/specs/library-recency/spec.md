## MODIFIED Requirements

### Requirement: Three mutually exclusive recency states
The system SHALL derive at most one recency state per game from recorded data, never storing the state itself. The states SHALL be: **newly added** — arrived recently and not yet played; **newly played** — its first ever recorded session happened recently; and **returned to play** — played recently after a long period with no play. Every state SHALL expire on its own, so a game with no recent change carries no state.

Where more than one state's conditions hold, the system SHALL resolve to exactly one, preferring newly played over returned to play, and returned to play over newly added.

#### Scenario: Precedence selects one recency state
- **WHEN** more than one recency state's conditions hold for a game
- **THEN** the game carries only the highest-precedence state: newly played over returned to play, and returned to play over newly added

### Requirement: Newly acquired games are announced for a bounded period
When a poll observes games the library has no record of, the system SHALL announce them, naming how many arrived and identifying at least some of them. The announcement SHALL be dismissible, SHALL expire on its own within a day of the poll that produced it, and SHALL NOT require the app to have been running for either to happen.

#### Scenario: Games acquired
- **WHEN** a poll observes one or more games the library has no record of
- **THEN** an announcement is presented reporting how many arrived

#### Scenario: Announcement identifies the games
- **WHEN** the announcement is presented
- **THEN** it names at least one arrived game, and reports the count of any it does not name

#### Scenario: Dismissed
- **WHEN** the user dismisses the announcement
- **THEN** it is not presented again for that set of games

#### Scenario: Expiry without dismissal
- **WHEN** a day has passed since the poll that produced the announcement
- **THEN** it is no longer presented, whether or not the app was running during that day

#### Scenario: A later acquisition supersedes an earlier one
- **WHEN** a further poll observes more previously unknown games
- **THEN** the announcement describes the newer set and is presented again even if the earlier one had been dismissed

#### Scenario: No acquisition, no announcement
- **WHEN** a poll observes no previously unknown games
- **THEN** no announcement is presented and any existing one is left as it was

#### Scenario: Announcement is not restored
- **WHEN** a backup is restored
- **THEN** no announcement from the backed-up device is presented

#### Scenario: Announcement does not block the surface
- **WHEN** the announcement is presented
- **THEN** the surface behind it remains usable and the announcement is not modal
