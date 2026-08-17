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

### Requirement: Observation ordering watermark

The system SHALL advance `lastObservedAt` on the current-state document for every successful
Steam observation, including same-game observations that do not append a transition. An
observation at or before the stored `lastObservedAt` SHALL be ignored inside the transaction,
so an older observation cannot overwrite newer state.

#### Scenario: Same-game observation advances the watermark

- **WHEN** a successful poll reports the same game at a newer timestamp
- **THEN** `lastObservedAt` advances
- **AND** no presence transition document is appended
- **AND** `since` and `updatedAt` retain their stored values

#### Scenario: Older observation is ignored

- **WHEN** an observation timestamp is older than or equal to `lastObservedAt`
- **THEN** the current-state document and presence log remain unchanged

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

### Requirement: Failures leave state untouched

The system SHALL treat a failed or malformed Steam API response as an absence of information rather than as evidence the user is offline. A failed fetch SHALL NOT modify the current document or append to the presence log.

#### Scenario: API error does not fabricate an offline transition

- **WHEN** the Steam API request fails or returns an unusable response
- **THEN** the `current` document is left unmodified
- **AND** no document is appended to the `presence` subcollection
- **AND** the failure is logged

#### Scenario: Online without game attribution is distinguishable

- **WHEN** a response reports the user as online but omits game fields
- **THEN** the observation is recorded with no game ID
- **AND** the function logs that game attribution was unavailable

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

The system SHALL stamp every document it writes with a schema version field set to `1`, so that a reader can identify the shape without inferring it from which fields are present.

#### Scenario: Current document is versioned

- **WHEN** the `current` document is written
- **THEN** it contains a schema version field set to `1`

#### Scenario: Presence documents are versioned

- **WHEN** a document is appended to the `presence` subcollection
- **THEN** it contains a schema version field set to `1`

### Requirement: Liveness heartbeat

The system SHALL emit a distinct log entry on every poll that completes a successful Steam fetch and a successful Firestore interaction. The entry SHALL NOT be emitted when the Steam fetch fails or the Firestore interaction fails, so that its absence indicates a broken pipeline rather than an idle user.

Invocation count is not a sufficient health signal: a revoked API key leaves the function running and returning success while recording nothing. The current-state document is not sufficient either, because a healthy poller writes nothing while the user is not playing.

#### Scenario: Successful poll emits the heartbeat

- **WHEN** a poll fetches presence successfully and completes its Firestore interaction
- **THEN** a heartbeat log entry is emitted
- **AND** it is emitted whether or not the poll resulted in a write

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
