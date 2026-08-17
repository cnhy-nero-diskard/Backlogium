# cloud-presence-poller

## ADDED Requirements

### Requirement: Transition recording is atomic with the state it was decided from
The decision that an observation represents a game change, and the writes that record it,
SHALL form one atomic and isolated operation against the stored current state. A second
invocation that observes the same transition SHALL either see the already-recorded state and
write nothing, or be retried against it.

#### Scenario: Overlapping invocations observing one transition
- **WHEN** two invocations read the same stored state and each independently concludes the
  same game change is new
- **THEN** exactly one transition record is written, and the other invocation writes nothing

#### Scenario: Same-game poll under isolation
- **WHEN** an observation reports the same game as the stored state
- **THEN** no transition write occurs, `lastObservedAt` advances, and the comparison that
  reached that conclusion was made against state that could not change underneath it

#### Scenario: Genuine transition
- **WHEN** an observation reports a different game than the stored state
- **THEN** the current state and a transition record are written together, and the marker for
  when the present state began is reset

#### Scenario: Contention resolves without duplication
- **WHEN** the stored state changes while an invocation is deciding
- **THEN** that invocation re-evaluates against the new state rather than committing a
  decision made from stale state

### Requirement: Successful observations advance an ordering watermark
Every successful Steam observation SHALL advance `lastObservedAt` on the current-state
document, including an observation that reports the same game and appends no transition.
An observation whose timestamp is older than or equal to `lastObservedAt` SHALL perform no
write at all. The watermark and transition decision SHALL be read and updated inside the
same transaction.

#### Scenario: Newer same-game observation establishes the watermark
- **WHEN** an observation reports the stored game at a later timestamp
- **THEN** the current-state document's `lastObservedAt` advances
- **AND** no presence transition document is appended
- **AND** `since` and `updatedAt` retain their stored values

#### Scenario: Older transition cannot roll state backward
- **WHEN** an older different-game observation retries after a newer same-game observation
  has advanced `lastObservedAt`
- **THEN** the older observation writes nothing
- **AND** the current-state game and transition log remain at the newer state

### Requirement: Recording an observation more than once cannot duplicate a transition
Recording the same logical observation more than once — by overlapping invocation, retry, or
redelivery — SHALL NOT produce more than one transition record. A retry MAY update the
ordering watermark, but SHALL NOT append another transition. Any documented claim about this
guarantee SHALL describe the mechanism that actually provides it.

#### Scenario: The same observation recorded twice
- **WHEN** one logical observation is recorded twice
- **THEN** the transition log contains one entry for it, and the current-state watermark is
  at least as new as the observation

#### Scenario: No two adjacent entries share a game
- **WHEN** the transition log is read
- **THEN** no two adjacent entries share a game identifier, so every entry is a genuine game
  change

#### Scenario: Documented guarantees match the implementation
- **WHEN** the code documents an idempotency or uniqueness guarantee
- **THEN** that documentation names the mechanism providing it, and does not attribute it to a
  mechanism that does not

### Requirement: Invocations do not overlap
The scheduled poller SHALL be configured so that one invocation cannot still be running when
the next begins, bounding concurrency independently of the atomicity guarantee rather than
relying on it alone.

#### Scenario: A slow observation
- **WHEN** an observation takes longer than usual because the upstream service is slow
- **THEN** the invocation ends before the next scheduled one begins, recording nothing rather
  than overlapping

#### Scenario: Concurrency is bounded
- **WHEN** the scheduler fires while an invocation is in flight
- **THEN** no additional concurrent instance of the poller is started

#### Scenario: A missed poll is not retried
- **WHEN** an invocation ends without recording an observation
- **THEN** no retry is attempted, because the next scheduled poll supersedes it
