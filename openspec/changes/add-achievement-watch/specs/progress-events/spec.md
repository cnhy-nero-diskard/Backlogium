## ADDED Requirements

### Requirement: An unlock event is produced only by the watch
An achievements-unlocked event SHALL be produced only by an achievement watch observation. The
periodic sync, a manual sync, and the deferred reconciliation pass SHALL store unlocks as they do
today and SHALL produce no such event, since each can discover an arbitrary number of unlocks
unrelated to anything happening now.

#### Scenario: Sync discovers unlocks
- **WHEN** a periodic or manual sync stores achievements that were not previously recorded as
  unlocked
- **THEN** no achievements-unlocked event is produced

#### Scenario: Reconciliation discovers unlocks
- **WHEN** a reconciliation pass stores previously unrecorded unlocks across the library
- **THEN** no achievements-unlocked event is produced

#### Scenario: Watch discovers an unlock
- **WHEN** a watch observation after that session's baseline finds a new unlock
- **THEN** an achievements-unlocked event is produced

#### Scenario: No duplicate from a following sync
- **WHEN** the watch produces an event for an unlock and a later sync observes the same unlock
- **THEN** no second event is produced for it

### Requirement: An unlock event does not participate in the transition protocol
An achievements-unlocked event SHALL be produced from an observation rather than from a comparison of
derived values before and after a persistence. It SHALL NOT require a pending-transition record, SHALL
NOT take part in transition recovery, and SHALL NOT be reseeded by a delivery baseline.

#### Scenario: No pending transition recorded
- **WHEN** an achievements-unlocked event is produced
- **THEN** no pending transition record is written for it

#### Scenario: Recovery is unaffected
- **WHEN** transition recovery resolves an abandoned persistence
- **THEN** pending achievements-unlocked events are neither consumed, cleared, nor duplicated by it

#### Scenario: Recompute does not reseed it
- **WHEN** a recompute establishes or updates a delivery baseline
- **THEN** a pending achievements-unlocked event is unaffected

## MODIFIED Requirements

### Requirement: Progress event vocabulary
The system SHALL represent player-facing progress transitions as a closed set of events, each
carrying enough detail to be presented without re-reading state: a level-up carrying the level
departed from and the level reached; a quest-met event carrying the date; a streak-milestone event
carrying the streak length reached; a streak-broken event carrying the length of the streak
that ended; and an achievements-unlocked event carrying the game whose achievements unlocked and the
identity of each achievement observed, so it can be presented without querying storage.

#### Scenario: Level-up carries both ends of the transition
- **WHEN** a level-up event is produced
- **THEN** it carries both the level the player left and the level the player reached

#### Scenario: Streak milestone honours the configured interval
- **WHEN** an earned recompute raises the current streak to a positive multiple of the milestone
  interval that has not previously been delivered
- **THEN** a streak-milestone event carrying that streak length is produced

#### Scenario: Streak break carries the lost length
- **WHEN** an earned recompute lowers the current streak to zero from a positive value
- **THEN** a streak-broken event carrying the previous length is produced

#### Scenario: A streak that does not reach a milestone produces no milestone event
- **WHEN** an earned recompute raises the current streak to a value that is not a multiple of the
  milestone interval
- **THEN** no streak-milestone event is produced

#### Scenario: An unlock event carries its achievements
- **WHEN** an achievements-unlocked event is produced
- **THEN** it carries the game's identity and each observed achievement's identity, sufficient to
  present the event without reading storage

#### Scenario: An unlock event names its game
- **WHEN** an achievements-unlocked event is presented
- **THEN** the game it belongs to is identifiable from the event itself

### Requirement: Threshold crossings collapse into a single event
Where an earned recompute crosses several thresholds of the same kind at once, the system SHALL
produce one event describing the whole transition rather than one event per threshold. Where a single
watch observation finds several achievements newly unlocked, the system SHALL likewise produce one
achievements-unlocked event carrying all of them rather than one event per achievement.

#### Scenario: Multiple levels gained in one sync
- **WHEN** a single sync raises the player's level from 4 to 7
- **THEN** exactly one level-up event is produced, carrying 4 and 7

#### Scenario: Multiple streak milestones passed at once
- **WHEN** a single earned recompute raises the current streak from 6 to 15, passing both the 7-day
  and 14-day milestones
- **THEN** exactly one streak-milestone event is produced, carrying the highest milestone reached

#### Scenario: Several achievements unlocked in one observation
- **WHEN** one watch observation finds six achievements newly unlocked
- **THEN** exactly one achievements-unlocked event is produced, carrying all six

#### Scenario: Unlocks in separate observations are separate events
- **WHEN** two consecutive watch observations each find a new unlock
- **THEN** two achievements-unlocked events are produced, one per observation

### Requirement: Simultaneous events carry a presentation priority
Where one earned recompute produces several events, the system SHALL make all of them available
and SHALL define a stable priority order — level-up, then streak milestone, then achievements
unlocked, then quest met, then streak broken — so that a surface able to present only one presents
the most significant.

#### Scenario: A sync produces several events
- **WHEN** one sync levels the player up, satisfies today's quest, and reaches a streak milestone
- **THEN** all three events are available, ordered level-up first

#### Scenario: A surface that presents one event
- **WHEN** a surface can present only a single event and several are pending
- **THEN** it presents the highest-priority pending event, and the others remain unacknowledged

#### Scenario: An unlock alongside a level-up
- **WHEN** an unlock event and a level-up event are both pending
- **THEN** the level-up is presented first, and the unlock remains available

#### Scenario: An unlock alongside a quest-met
- **WHEN** an unlock event and a quest-met event are both pending
- **THEN** the unlock is presented first, and the quest-met remains available
