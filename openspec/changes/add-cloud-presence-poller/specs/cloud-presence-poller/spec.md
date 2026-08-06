## ADDED Requirements

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

The system SHALL maintain the `players/{steamId}` document (referred to below as the current-state document) reflecting the most recently observed presence, containing the schema version, the presence state, the app ID and name of any game in progress, the time the present state began, and the time of the most recent state change.

#### Scenario: First observation creates the document

- **WHEN** a poll succeeds and no `current` document exists
- **THEN** the function creates it with the observed state
- **AND** `since` and `updatedAt` are both set to the observation time

#### Scenario: Session duration is derivable

- **WHEN** the user has been in the same game across many consecutive polls
- **THEN** `since` still holds the time that game was first observed
- **AND** `updatedAt` holds the most recent observation time

### Requirement: Write on change only

The system SHALL write to Firestore only when the observed presence differs materially from the stored current state. A poll observing no change in presence state or game SHALL perform no write.

#### Scenario: Unchanged presence performs no write

- **WHEN** a poll returns the same presence state and game ID as the stored `current` document
- **THEN** no Firestore write occurs
- **AND** `since` and `updatedAt` retain their stored values

#### Scenario: Changed presence updates current and appends history

- **WHEN** a poll returns a presence state or game ID differing from the stored `current` document
- **THEN** `current` is updated with the new state
- **AND** `since` is reset to the observation time
- **AND** one document is appended to the `presence` subcollection

### Requirement: Append-only presence log

The system SHALL record each observed transition as a document in the `players/{steamId}/presence` subcollection, keyed by the observation timestamp, containing the schema version, the observation time, the raw persona state, and the game ID and name if present.

#### Scenario: Transitions are recorded in order

- **WHEN** the user starts a game and later stops it
- **THEN** the subcollection contains one document for the start transition and one for the stop transition

#### Scenario: Duplicate delivery does not duplicate history

- **WHEN** an invocation for an observation timestamp already recorded is retried or delivered more than once
- **THEN** the existing document for that timestamp is overwritten
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

### Requirement: Client access is denied

The system SHALL deploy Firestore security rules denying all read and write access to client SDKs. The poller SHALL write through the Firebase Admin SDK, whose service-account credentials bypass security rules.

#### Scenario: Client reads are refused

- **WHEN** any client SDK attempts to read or write any path
- **THEN** the request is denied

#### Scenario: The poller is unaffected by rules

- **WHEN** the poller writes presence data
- **THEN** the write succeeds regardless of the deployed rules
