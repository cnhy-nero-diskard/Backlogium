## ADDED Requirements

### Requirement: Poll coverage record

The system SHALL record, for each hour, that polling occurred during that hour, by incrementing a counter on a document in the `players/{steamId}/coverage` subcollection keyed by the observation hour. The counter SHALL be incremented on every poll that completed a successful Steam fetch, whether or not that poll resulted in a presence write.

A reader of the presence log cannot otherwise distinguish a long play session from a poller outage. Because writes occur only on game change, silence in the log is produced identically by a healthy idle poller and by a dead one, and the liveness heartbeat lives in Cloud Logging where no client can reach it. Coverage is the observable that makes an unobserved window recognisable as unobserved.

#### Scenario: Successful poll records coverage

- **WHEN** a poll fetches presence successfully
- **THEN** the coverage counter for the observation hour is incremented

#### Scenario: Coverage is recorded independently of writes

- **WHEN** a poll returns the same game ID as the stored current-state document
- **THEN** no presence write occurs
- **AND** the coverage counter for the observation hour is still incremented

#### Scenario: Failed fetch records no coverage

- **WHEN** the Steam request fails or returns an unusable response
- **THEN** the coverage counter is not incremented
- **AND** the hour reflects only the polls that actually observed something

#### Scenario: An outage is visible as a coverage gap

- **WHEN** the poller does not run for several hours
- **THEN** those hours have no coverage document, or a count far below the number of minutes in an hour
- **AND** a reader can identify the window as unobserved

#### Scenario: Coverage carries no presence data

- **WHEN** a coverage document is written
- **THEN** it records the count of polls for that hour and the schema version
- **AND** it records no game ID, persona state, or derived value

#### Scenario: Coverage documents are versioned

- **WHEN** a coverage document is created
- **THEN** it contains a schema version field set to `1`

## MODIFIED Requirements

### Requirement: Write on game change only

The system SHALL write to the presence log and the current-state document only when the observed game ID differs from the stored game ID. A change in persona state alone SHALL NOT constitute a material change and SHALL NOT produce a write to either. Persona state is still recorded as a field on every such document written, so the raw value at each transition is preserved.

This requirement governs the presence log and the current-state document. It does not govern the coverage subcollection, whose purpose is to record that polling happened at all and which therefore SHALL be written on every successful poll.

Persona state is excluded from change detection because Steam moves an idle account between online, away, and snooze automatically. Those transitions carry no information about what is being played, and treating them as material both fills the log with idle churn and splits a single continuous play session into fragments.

#### Scenario: Unchanged game performs no write

- **WHEN** a poll returns the same game ID as the stored current-state document
- **THEN** no write to the presence log or the current-state document occurs
- **AND** `since` and `updatedAt` retain their stored values

#### Scenario: Idling during a session does not split it

- **WHEN** the persona state changes from online to away while the same game remains in progress
- **THEN** no write to the presence log or the current-state document occurs
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

#### Scenario: Coverage is exempt

- **WHEN** a poll observes no game change
- **THEN** the presence log is unchanged
- **AND** the coverage counter for that hour is nonetheless incremented
