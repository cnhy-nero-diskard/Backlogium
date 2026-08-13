# steam-sync

## ADDED Requirements

### Requirement: At most one poll runs at a time
The system SHALL ensure that no two Steam polls execute concurrently, regardless of
whether each was scheduled periodically or requested manually. A manual request made
while a poll is already running SHALL NOT start a second poll, and the interface SHALL
reflect that the request was absorbed rather than appearing to do nothing.

#### Scenario: Manual request during a running poll
- **WHEN** the user requests a sync while a scheduled poll is already running
- **THEN** no second poll begins, and the observed playtime increase is recorded exactly
  once

#### Scenario: Manual request while idle
- **WHEN** the user requests a sync and no poll is running
- **THEN** a poll begins promptly

#### Scenario: Interface reflects an absorbed request
- **WHEN** a manual request is absorbed because a poll is already in flight
- **THEN** the interface indicates that a sync is in progress rather than leaving the
  request without visible effect

#### Scenario: Daily totals are not double-credited
- **WHEN** two poll requests overlap in time for the same playtime increase
- **THEN** the day's recorded minutes increase by that increase once, not twice

### Requirement: A poll's persistence is atomic
All database changes derived from a single poll — synthesized sessions, per-game
playtime baselines, daily progress, player profile state, and derived gamification
values — SHALL be committed as one unit that either applies completely or not at all. A
playtime baseline SHALL NOT be advanced unless the progress that advance represents is
committed with it.

#### Scenario: Interruption during persistence
- **WHEN** a poll's persistence is interrupted partway through
- **THEN** no part of it has been applied, and the stored baseline still reflects the
  state before the poll, so the same increase is observed again on the next poll

#### Scenario: Baseline and credited progress move together
- **WHEN** a poll advances a game's playtime baseline
- **THEN** the daily progress crediting that advance is committed in the same unit, so
  no observed minutes can be stranded behind a moved baseline

#### Scenario: Network work precedes persistence
- **WHEN** a poll needs remote data to compute what it will persist
- **THEN** all such data is fetched before persistence begins, so no remote call occurs
  partway through a commit

#### Scenario: Failure preserves last-good data
- **WHEN** a poll fails at any point
- **THEN** previously stored data is unchanged, and the failure is surfaced rather than
  partially applied

### Requirement: The sync writes only Steam-owned fields
When persisting a poll, the system SHALL update only those per-game fields for which
Steam is the authority — name, icon, total and recent playtime, the diff baseline, and
the sync timestamp. Fields the app owns — focus tagging, target minutes, and imported
history offsets — SHALL NOT be written by a poll, so that a concurrent user action or
import cannot be reverted by it.

#### Scenario: Focus toggled during a poll
- **WHEN** the user changes a game's focus flag while a poll is in progress
- **THEN** that change survives the poll's persistence

#### Scenario: History import during a poll
- **WHEN** an imported history offset is written for a game while a poll is in progress
- **THEN** that offset survives the poll's persistence

#### Scenario: A newly owned game
- **WHEN** a poll observes a game not previously stored
- **THEN** the game is created with Steam-owned fields populated and app-owned fields at
  their documented defaults

### Requirement: Derived values are committed with the configuration that produced them
The rule configuration used to derive experience, quest results, and streaks SHALL be
the configuration in effect at the moment those derived values are committed, so
persisted rules and persisted derived state cannot disagree.

#### Scenario: Rules changed during a poll
- **WHEN** the user changes rule configuration while a poll is in progress
- **THEN** the poll does not commit derived values computed under the superseded
  configuration

#### Scenario: Configuration read position
- **WHEN** a poll derives values that depend on rule configuration
- **THEN** the configuration is read within the same unit that commits those values,
  with no intervening remote call

### Requirement: Profile fields are written by their owning domain only
Each writer of the player profile SHALL update only the fields it owns — sync status,
Steam identity, gamification aggregates, or history-import state — rather than replacing
the whole record, so that concurrent writers in different domains cannot overwrite each
other's fields.

#### Scenario: Concurrent writes in different domains
- **WHEN** one operation updates gamification aggregates and another updates sync status
- **THEN** both updates are present afterwards

#### Scenario: Recording a sync failure
- **WHEN** a poll fails and records the failure on the profile
- **THEN** only the failure-reporting fields change, leaving identity and aggregates
  untouched
