## ADDED Requirements

### Requirement: Transitions record the coverage of the state they replace

Every appended presence transition SHALL record the time of the last successful observation of
the state it replaces, as `prevLastObservedAt`. A reader comparing that value against the
transition's own timestamp SHALL therefore be able to distinguish a state observed continuously
up to the moment it changed from one whose final stretch went unobserved.

The log is otherwise a record of transitions with no account of the intervals between them.
Continuous observation and a poller that stopped running produce an identical transition log, so
an interval's duration cannot be trusted as played time without this value. Because Steam exposes
no historical presence and this log is retained indefinitely for exactly that reason, an interval
recorded without coverage is permanently ambiguous — the evidence needed to resolve it later does
not exist.

Where no prior state existed, the field SHALL be absent rather than set to a substitute value:
"there was no prior state" and "the prior state was last confirmed at the epoch" are different
facts, and a reader must not have to distinguish them by guessing.

#### Scenario: Continuous observation up to a change

- **WHEN** successive polls observe the same game and a later poll observes a different one
- **THEN** the appended transition records a last-confirmed time no earlier than the preceding
  poll, so the interval it closes is identifiable as continuously observed

#### Scenario: Observation lapsed before a change

- **WHEN** the poller records no successful observation for a period while a game is in progress,
  and its next successful observation reports a different game
- **THEN** the appended transition records the last confirmed time from before the lapse
- **AND** the unobserved stretch between that time and the transition is identifiable as such

#### Scenario: One record bounds both sides of a lapse

- **WHEN** a transition records a last-confirmed time materially earlier than its own timestamp
- **THEN** the record is sufficient to bound both the end of the state being replaced and the
  earliest point at which the state being introduced may have begun, without a second field

#### Scenario: The first transition has no predecessor

- **WHEN** the first transition for a player is appended and no current-state document existed
- **THEN** the last-confirmed field is absent from the written document
- **AND** it is not written as zero, as the observation time, or as any other substitute

#### Scenario: Unchanged polls still append nothing

- **WHEN** a poll observes the same game as the stored state
- **THEN** no transition is appended, exactly as before
- **AND** the watermark that poll advances is the value a later transition will record as its
  last-confirmed time

#### Scenario: Coverage is recorded, never derived

- **WHEN** a transition is appended
- **THEN** it records observation timestamps only
- **AND** it contains no duration, elapsed time, gap length, or coverage proportion computed from
  them

#### Scenario: Transitions predating this requirement are of unknown coverage

- **WHEN** a reader encounters a presence transition carrying no last-confirmed field
- **THEN** the interval it closes is treated as of unknown coverage
- **AND** it is not treated as continuously observed

## MODIFIED Requirements

### Requirement: Schema version stamp

The system SHALL stamp every document it writes with a schema version field identifying that
document's shape, so that a reader can identify the shape without inferring it from which fields
are present. The version SHALL identify one shape rather than the system as a whole: the
current-state document is version `1`, and a presence transition document is version `2`.

Versions SHALL advance independently. A change to one document's shape SHALL NOT bump the version
of a shape it did not alter, so that a reader's branch on one version is not invalidated by a
change it is not affected by.

#### Scenario: Current document is versioned

- **WHEN** the `current` document is written
- **THEN** it contains a schema version field set to `1`

#### Scenario: Presence documents are versioned

- **WHEN** a document is appended to the `presence` subcollection
- **THEN** it contains a schema version field set to `2`

#### Scenario: An unaltered shape keeps its version

- **WHEN** the presence transition shape gains a field and the current-state shape gains none
- **THEN** the presence transition version advances and the current-state version does not

#### Scenario: A reader identifies a shape without sniffing fields

- **WHEN** a reader encounters a document written by this system
- **THEN** the document's own version field states which shape it is
- **AND** the reader is not required to infer it from which fields happen to be present
