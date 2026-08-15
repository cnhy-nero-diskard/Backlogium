## ADDED Requirements

### Requirement: Recency fields round-trip without manufacturing events
A backup SHALL carry each game's recorded arrival time, last-played time, and recorded return from
dormancy, and an import SHALL restore them as recorded. An import SHALL NOT write any of those values
for a game other than what the backup carried, so that restoring a library is never mistaken for
acquiring one or for playing one.

#### Scenario: Fields exported
- **WHEN** a backup is exported
- **THEN** each game's arrival time, last-played time, and recorded return are included, with an
  explicit absence where any is unknown

#### Scenario: Fields imported
- **WHEN** a backup carrying those times is imported
- **THEN** they are restored, unchanged, for the games they belong to

#### Scenario: Inserted games are not stamped as arrivals
- **WHEN** an import inserts games that are not present in the current library
- **THEN** no arrival time is written for them other than one carried by the backup

#### Scenario: Import records no returns
- **WHEN** an import inserts or updates games
- **THEN** no return from dormancy is written other than one carried by the backup

#### Scenario: Import produces no announcement
- **WHEN** an import inserts previously unknown games
- **THEN** no acquisition announcement is presented

#### Scenario: Older backup lacking the fields
- **WHEN** a backup written before these fields existed is imported
- **THEN** it imports successfully, the affected games have no arrival time and no recorded return,
  and their last-played times are filled in by the next sync

#### Scenario: Restored data is interpreted on its own timeline
- **WHEN** an import completes
- **THEN** any recency state a game carries follows from the times the backup recorded, so states
  recorded long ago have already expired and states recorded recently are still current
