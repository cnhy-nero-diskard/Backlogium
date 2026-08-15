## ADDED Requirements

### Requirement: Recency fields round-trip without manufacturing arrivals
A backup SHALL carry each game's recorded arrival time and last-played time, and an import SHALL
restore them. An import SHALL NOT record an arrival time for a game it inserts beyond what the
backup itself carried, so that restoring a library is never mistaken for acquiring one.

#### Scenario: Fields exported
- **WHEN** a backup is exported
- **THEN** each game's arrival time and last-played time are included, with an explicit absence
  where either is unknown

#### Scenario: Fields imported
- **WHEN** a backup carrying arrival and last-played times is imported
- **THEN** those values are restored for the games they belong to

#### Scenario: Inserted games are not stamped as arrivals
- **WHEN** an import inserts games that are not present in the current library
- **THEN** no arrival time is written for them other than one carried by the backup

#### Scenario: Import produces no announcement
- **WHEN** an import inserts previously unknown games
- **THEN** no acquisition announcement is presented

#### Scenario: Older backup lacking the fields
- **WHEN** a backup written before these fields existed is imported
- **THEN** it imports successfully, the affected games have no arrival time, and their last-played
  times are filled in by the next sync

#### Scenario: Import produces no recency states
- **WHEN** an import completes
- **THEN** no game carries a recency state as a result of the import
