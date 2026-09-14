## ADDED Requirements

### Requirement: Transitions record the coverage of the state they replace

Every appended presence transition SHALL record the time of the last successful observation of
the state it replaces, as `prevLastObservedAt`. A reader comparing that value against the
transition's own timestamp SHALL therefore be able to distinguish a state observed continuously
up to the moment it changed from one whose final stretch went unobserved.

A same-game observation that advances the watermark SHALL retain the largest
consecutive-observation step seen within the state as `coverageLapseFrom`
(the stored watermark the winning step replaces) and
`coverageLapseRecoveredAt` (that step's observation time) on the current-state
document, and copy them onto the next transition as `prevCoverageLapseFrom`
and `prevCoverageLapseRecoveredAt`. A later step replaces the retained pair
only when strictly longer; ties keep the earlier pair. The poller SHALL apply
no tolerance cutoff to decide whether a step is worth retaining, and comparing
spans selects which raw pair to keep but writes no duration: every retained
value stays a raw observation timestamp, and whether a span of a minute or an
hour counts as a lapse is the reader's decision. A reader comparing that pair
SHALL therefore be able to distinguish an unobserved span inside the replaced
state from a merely stale tail, even though polls confirming the same game
arrived between the lapse and the change.

Smaller steps within the same state are not separately recorded, but a reader
applying any tolerance to the retained pair reaches the same verdict as if it
had seen every step: any interior step exceeding the tolerance implies the
retained largest step exceeds it, and vice versa. The tail watermark still
bounds the change itself, and the new state after a transition starts with no
retained pair.

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

#### Scenario: Observation resumes after a lapse before a change

- **WHEN** the poller records no successful observation for a period while a game is in progress,
  then observes the same game again, and a later poll observes a different game
- **THEN** the appended transition records the pre-lapse confirmed time and the resuming
  observation's time alongside the last-confirmed time
- **AND** the unobserved span between the first two is identifiable as such rather than the
  state reading as continuously observed up to the change

#### Scenario: A short lapse is retained for a stricter reader

- **WHEN** the poller records no successful observation for two minutes while a game is in
  progress, then observes the same game again, and a later poll observes a different game
- **THEN** the appended transition records the pre-lapse confirmed time and the resuming
  observation's time alongside the last-confirmed time
- **AND** a reader with a tolerance below two minutes identifies the span as unobserved
  rather than reading the state as continuously observed up to the change

#### Scenario: A later lapse is retained behind an earlier on-cadence step

- **WHEN** successive same-game polls confirm a game on cadence, the poller then records
  no successful observation for a period, observes the same game again, and a later poll
  observes a different game
- **THEN** the appended transition records the pre-lapse confirmed time of the later
  span and its resuming observation's time alongside the last-confirmed time
- **AND** a reader identifies the later span as unobserved rather than reading the state
  as continuously observed because the first retained step was short

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
current-state document is version `2`, and a presence transition document is version `3`.

Versions SHALL advance independently. A change to one document's shape SHALL NOT bump the version
of a shape it did not alter, so that a reader's branch on one version is not invalidated by a
change it is not affected by.

#### Scenario: Current document is versioned

- **WHEN** the `current` document is written
- **THEN** it contains a schema version field set to `2`

#### Scenario: Presence documents are versioned

- **WHEN** a document is appended to the `presence` subcollection
- **THEN** it contains a schema version field set to `3`

#### Scenario: Each altered shape advances its own version

- **WHEN** the current-state shape gains the retained-step pair and the presence transition
  shape gains the copied pair
- **THEN** the current-state version advances from `1` to `2` and the presence transition
  version advances from `2` to `3`
- **AND** a future change to one shape advances only its own version

#### Scenario: A reader identifies a shape without sniffing fields

- **WHEN** a reader encounters a document written by this system
- **THEN** the document's own version field states which shape it is
- **AND** the reader is not required to infer it from which fields happen to be present
