# steam-sync

## ADDED Requirements

### Requirement: Concurrent polls cannot double-count
A playtime increase SHALL be recorded exactly once no matter how many polls observe it. The
system SHALL guarantee this by deriving each poll's committed delta from baselines read within
the same transaction that commits it, so a poll committing after another has already advanced
a baseline records nothing further. This guarantee SHALL NOT depend on scheduling behaviour,
work-request identity, or the two polls running in the same process.

Additionally, a manual request made while a poll is already running SHOULD be absorbed rather
than starting redundant remote work, and the interface SHALL reflect that rather than
appearing to do nothing.

#### Scenario: Manual request during a running poll
- **WHEN** the user requests a sync while a scheduled poll is already running
- **THEN** the observed playtime increase is recorded exactly once

#### Scenario: Two polls commit the same observed increase
- **WHEN** two polls both observe the same playtime increase and both reach their commit
- **THEN** the second commit derives its delta from the already-advanced baseline and records
  no additional session and no additional minutes

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

### Requirement: A poll's raw persistence is atomic
The raw data a poll produces — synthesized sessions, per-game playtime baselines, daily
progress, and player profile fields — SHALL be committed as one unit that either applies
completely or not at all. A playtime baseline SHALL NOT be advanced unless the progress that
advance represents is committed with it.

Derived gamification values are written separately, immediately afterwards, through the
existing recoverable protocol that spans the database and settings storage. They are excluded
from this unit because that protocol cannot execute inside a database transaction, and because
derived values can be regenerated from committed raw data whereas raw data cannot be
regenerated from anything.

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

#### Scenario: Interruption between raw and derived writes
- **WHEN** a poll commits its raw data and is interrupted before derived values are written
- **THEN** the raw data remains committed, the incomplete derived write is detected on the next
  attempt, and derived values are regenerated from the committed raw data

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

### Requirement: Derived values record and verify the configuration that produced them
Rule configuration SHALL carry a version that changes whenever the configuration changes, and
SHALL be read together with that version. Before derived values are written, the system SHALL
verify that the version is still current and SHALL refuse the write if it is not. Stored
derived values SHALL record the version that produced them, so persisted rules and persisted
derived state can be compared rather than assumed to agree.

Because rule configuration and derived values are held in separate stores that cannot commit
together, this requirement is satisfied by detecting and refusing a superseded write, not by
making the two writes atomic.

#### Scenario: Rules changed during a poll
- **WHEN** the user changes rule configuration after a poll has computed derived values but
  before that poll writes them
- **THEN** the poll does not write those derived values, and a recomputation under the current
  configuration follows

#### Scenario: Raw data survives a refused derived write
- **WHEN** a derived write is refused because the configuration changed
- **THEN** the poll's observed sessions, playtime baselines, and daily progress are still
  committed, because that data is unrecoverable and does not depend on configuration

#### Scenario: Version is recorded with the values
- **WHEN** derived values are written
- **THEN** the configuration version that produced them is stored with them

#### Scenario: Disagreement is detectable
- **WHEN** stored derived values and the current configuration are compared
- **THEN** a mismatch is identifiable from the stored version rather than being invisible

#### Scenario: Configuration unchanged
- **WHEN** the configuration is unchanged between computation and writing
- **THEN** the derived values are written and stamped with that version

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
