# cloud-presence-poller

## Purpose

Defines a server-side Steam presence poller: a scheduled Cloud Function that samples the
configured account's Steam presence every minute and records observed game transitions to
Firestore as a durable, append-only log, so that play history continues to accumulate while
the device is off. It records observations only and derives nothing.

## Requirements

### Requirement: Scheduled presence sampling

The system SHALL poll the Steam Web API `GetPlayerSummaries` endpoint for the configured Steam ID on a fixed one-minute schedule, from a Cloud Function deployed to the same region as the Firestore database.

#### Scenario: Scheduled invocation fetches presence

- **WHEN** the schedule fires
- **THEN** the function requests `GetPlayerSummaries` for the configured Steam ID
- **AND** the request authenticates with the Steam API key read from Secret Manager

#### Scenario: Steam ID is configured, not discovered

- **WHEN** the function starts
- **THEN** it reads the Steam ID from function configuration
- **AND** it does not read any Firestore document to determine whose presence to poll

### Requirement: Current-state document

The system SHALL maintain the `players/{steamId}` document (referred to below as the current-state document) reflecting the most recently observed presence, containing the schema version, the presence state, the app ID and name of any game in progress, the time the present state began, the time of the most recent state change, and the time of the most recent successful observation.

#### Scenario: First observation creates the document

- **WHEN** a poll succeeds and no `current` document exists
- **THEN** the function creates it with the observed state
- **AND** `since`, `updatedAt`, and `lastObservedAt` are all set to the observation time

#### Scenario: Session duration is derivable

- **WHEN** the user has been in the same game across many consecutive polls
- **THEN** `since` still holds the time that game was first observed
- **AND** `updatedAt` holds the most recent state-change time
- **AND** `lastObservedAt` holds the most recent successful observation time

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

### Requirement: Write on game change only

The system SHALL append a presence transition only when the observed game ID differs from the stored game ID. A successful observation MAY update the current-state ordering watermark without appending a transition. A change in persona state alone SHALL NOT constitute a material change or produce a transition. Persona state is still recorded as a field on every transition document written, so the raw value at each transition is preserved.

Persona state is excluded from change detection because Steam moves an idle account between online, away, and snooze automatically. Those transitions carry no information about what is being played, and treating them as material both fills the log with idle churn and splits a single continuous play session into fragments.

#### Scenario: Unchanged game performs no write

- **WHEN** a poll returns the same game ID as the stored current-state document
- **THEN** no presence transition is written
- **AND** `lastObservedAt` advances
- **AND** `since` and `updatedAt` retain their stored values

#### Scenario: Idling during a session does not split it

- **WHEN** the persona state changes from online to away while the same game remains in progress
- **THEN** no presence transition is written
- **AND** `lastObservedAt` advances
- **AND** `since` continues to mark the time the game was first observed

#### Scenario: Changed game updates current and appends history

- **WHEN** a poll returns a game ID differing from the stored current-state document
- **THEN** the current-state document is updated with the new state
- **AND** `since` is reset to the observation time
- **AND** one document is appended to the `presence` subcollection

#### Scenario: Switching directly between games is recorded

- **WHEN** a poll returns a different game ID while the stored game ID is also non-null
- **THEN** one document is appended recording the new game
- **AND** no intervening document representing "not playing" is fabricated

#### Scenario: Consecutive entries always differ by game

- **WHEN** the presence log is read in timestamp order
- **THEN** no two adjacent documents share the same game ID

### Requirement: Append-only presence log

The system SHALL record each observed transition as a document in the `players/{steamId}/presence` subcollection, keyed by the observation timestamp, containing the schema version, the observation time, the raw persona state, and the game ID and name if present.

#### Scenario: Transitions are recorded in order

- **WHEN** the user starts a game and later stops it
- **THEN** the subcollection contains one document for the start transition and one for the stop transition

#### Scenario: Duplicate delivery does not duplicate history

- **WHEN** an invocation for an observation timestamp already recorded is retried or delivered more than once
- **THEN** the retry is ignored when its timestamp is equal to `lastObservedAt`
- **AND** the existing transition document is not rewritten
- **AND** no additional document is appended

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

Smaller steps within the same state are not separately recorded. The retained
pair preserves the verdict, not the locations: a reader applying any tolerance
to the retained pair reaches the same "does any interior step exceed my
tolerance?" verdict as if it had seen every step — any interior step exceeding
the tolerance implies the retained largest step exceeds it, and vice versa —
but it cannot recover where every exceeding step was. A reader that must
exclude unconfirmed spans SHALL therefore treat the whole replaced state's
interval as unconfirmed once the retained span exceeds its tolerance, rather
than excluding only the retained span. The tail watermark still
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

#### Scenario: Two lapses in one state preserve the verdict but not both locations

- **WHEN** one state observes `A@t0 → A@t1 → outage → A@t10 → A@t11 → outage → A@t18`
  and then transitions to another game, with both interior steps above a reader's
  tolerance
- **THEN** the appended transition retains only the largest of the two steps, and that
  retained span still exceeds the reader's tolerance
- **AND** the reader treats the whole replaced state's interval as unconfirmed rather
  than excluding only the retained span, because the smaller step's location is not
  recoverable from the retained pair

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

### Requirement: Operational logs carry no account or title identity

The system SHALL NOT write the configured Steam ID, a played game's app ID, or a played
game's name to its operational log output. Log entries SHALL be limited to fields that
describe an outcome or a fault — decision outcomes, HTTP status codes, exception text,
profile visibility state — and SHALL NOT permit reconstruction of who was playing what, or
when.

Firestore is the access-controlled home for observation data: client access is denied and
the poller writes through the Admin SDK. Log output has a different and weaker boundary —
anyone with log-viewer access, any configured sink, and any tool downstream of a sink can
read it, and retention or exports may outlive the Firestore state that produced the entry.
A log line naming an account and a title is therefore a second copy of activity metadata
outside the boundary that was designed to hold it.

Responsibility for this rule SHALL rest with a single component that log payloads pass
through, rather than with each call site individually, so that a log line added later cannot
reintroduce the disclosure by being written somewhere new.

#### Scenario: Steam ID never appears in a log entry

- **WHEN** any log entry is emitted, on any code path, including error and misconfiguration paths
- **THEN** it does not contain the configured Steam ID in any field or in its message text

#### Scenario: Played title never appears in a log entry

- **WHEN** an observation reports the player is in a game
- **THEN** no log entry emitted for that observation contains the game's app ID or its name

#### Scenario: Faults remain diagnosable

- **WHEN** a Steam request fails, returns a non-success status, returns unparseable content,
  or returns no matching player
- **THEN** the emitted entry still identifies which of those conditions occurred, and carries
  the status code or exception text needed to act on it

#### Scenario: Misconfiguration remains actionable without echoing the value

- **WHEN** Steam returns no player for the configured Steam ID
- **THEN** the entry states that the configured ID could not be resolved and names the
  setting to check, without reproducing the ID

#### Scenario: A new log call site inherits the rule

- **WHEN** a log entry is added on a new code path
- **THEN** it passes through the same component that enforces this requirement, so the rule
  applies without that call site restating it

### Requirement: Failures leave state untouched

The system SHALL treat a failed or malformed Steam API response as an absence of information rather than as evidence the user is offline. A failed fetch SHALL NOT modify the current document or append to the presence log.

Where this requirement obliges the system to log something, that log SHALL satisfy
"Operational logs carry no account or title identity". Logging the fault is required;
identifying the account or the title in it is not, and the two obligations do not conflict.

#### Scenario: API error does not fabricate an offline transition

- **WHEN** the Steam API request fails or returns an unusable response
- **THEN** the `current` document is left unmodified
- **AND** no document is appended to the `presence` subcollection
- **AND** the failure is logged, without the configured Steam ID

#### Scenario: Online without game attribution is distinguishable

- **WHEN** a response reports the user as online but omits game fields
- **THEN** the observation is recorded with no game ID
- **AND** the function logs that game attribution was unavailable, without the configured
  Steam ID

### Requirement: The poller does not derive sessions

The system SHALL NOT compute sessions, playtime totals, streaks, experience, or any other derived value. It records observations only; derivation remains the responsibility of the on-device engine.

#### Scenario: No derived fields are written

- **WHEN** any document is written by the poller
- **THEN** it contains only observed presence fields and observation timestamps
- **AND** it contains no session, duration, playtime, or experience value

### Requirement: Indefinite retention

The system SHALL retain presence log documents indefinitely. No TTL policy or scheduled deletion SHALL be configured, because Steam exposes no historical presence and an expired document is unrecoverable from any source.

#### Scenario: Old samples are preserved

- **WHEN** a presence document's observation time is arbitrarily far in the past
- **THEN** it remains readable
- **AND** no automated process has deleted it

#### Scenario: No expiry is configured

- **WHEN** the Firestore configuration is inspected
- **THEN** no TTL policy applies to the presence subcollection or to the current-state document

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

### Requirement: Liveness heartbeat

The system SHALL emit a distinct log entry on every poll that completes a successful Steam fetch and a successful Firestore interaction. The entry SHALL NOT be emitted when the Steam fetch fails or the Firestore interaction fails, so that its absence indicates a broken pipeline rather than an idle user.

Invocation count is not a sufficient health signal: a revoked API key leaves the function running and returning success while recording nothing. The current-state document now advances `lastObservedAt` after successful polls, but a log-based absence alert is cheaper and more direct to monitor than polling and interpreting a Firestore timestamp.

The heartbeat's monitoring value rests on the entry existing, not on its contents. It SHALL
therefore carry no account or title identity, per "Operational logs carry no account or title
identity". It MAY carry the write outcome, which distinguishes a recorded transition from an
unchanged observation without naming what was played. This constraint is load-bearing rather
than incidental: the heartbeat fires on every successful poll, so it is the highest-volume log
the system emits and the one whose accumulated stream would most completely reconstruct a
play history.

#### Scenario: Successful poll emits the heartbeat

- **WHEN** a poll fetches presence successfully and completes its Firestore interaction
- **THEN** a heartbeat log entry is emitted
- **AND** it is emitted whether or not the poll resulted in a write

#### Scenario: Heartbeat does not name what was played

- **WHEN** a heartbeat entry is emitted for a poll that observed the player in a game
- **THEN** the entry contains neither the game's app ID nor its name

#### Scenario: Failed fetch suppresses the heartbeat

- **WHEN** the Steam request fails or returns an unusable response
- **THEN** no heartbeat log entry is emitted

#### Scenario: Absence is alertable

- **WHEN** no heartbeat entry has been emitted for a sustained period
- **THEN** the condition is detectable by a monitoring policy without inspecting Firestore

### Requirement: Client access is denied

The system SHALL deploy Firestore security rules denying all read and write access to client SDKs. The poller SHALL write through the Firebase Admin SDK, whose service-account credentials bypass security rules.

#### Scenario: Client reads are refused

- **WHEN** any client SDK attempts to read or write any path
- **THEN** the request is denied

#### Scenario: The poller is unaffected by rules

- **WHEN** the poller writes presence data
- **THEN** the write succeeds regardless of the deployed rules
