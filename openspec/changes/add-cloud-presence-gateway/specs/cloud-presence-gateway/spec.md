## ADDED Requirements

### Requirement: Read-only access

The system SHALL expose an HTTPS interface over the recorded presence data that offers retrieval only. It SHALL provide no route that creates, modifies, or deletes any Firestore document, and SHALL reject any request method other than `GET` and `HEAD`.

#### Scenario: Write methods are refused

- **WHEN** a request arrives with method `POST`, `PUT`, `PATCH`, or `DELETE`
- **THEN** the request is refused with a method-not-allowed status
- **AND** no Firestore document is written

#### Scenario: Retrieval succeeds

- **WHEN** an authorized `GET` request arrives for a defined route
- **THEN** the recorded data is returned without modifying any stored document

### Requirement: Bearer token authorization

The system SHALL require every request to present a bearer token matching a secret read from Secret Manager, and SHALL compare the presented value against the expected value in constant time. A request without a valid token SHALL be refused before any Firestore read is issued.

#### Scenario: Missing token is refused

- **WHEN** a request arrives with no authorization header
- **THEN** the request is refused with an unauthorized status
- **AND** no Firestore read is issued

#### Scenario: Incorrect token is refused

- **WHEN** a request presents a token that does not match the configured secret
- **THEN** the request is refused with an unauthorized status
- **AND** the response body distinguishes no further between a missing, malformed, and incorrect token

#### Scenario: Valid token is served

- **WHEN** a request presents the configured token
- **THEN** the request proceeds to the requested route

### Requirement: Firestore remains closed to clients

The system SHALL leave the deployed Firestore security rules denying all client read and write access. The gateway SHALL read through the Firebase Admin SDK, whose service-account credentials bypass those rules, so that it remains the only path by which recorded presence can be read.

#### Scenario: Direct client reads are still refused

- **WHEN** a client SDK attempts to read any Firestore path
- **THEN** the request is denied

#### Scenario: The gateway reads regardless of rules

- **WHEN** an authorized request reaches the gateway
- **THEN** its Firestore read succeeds regardless of the deployed rules

### Requirement: Health and identity probe

The system SHALL expose a health route returning the schema version it serves, the Steam ID whose presence is recorded, the time of the most recent recorded observation, the time of the most recent poll known to have completed, and the earliest time for which coverage is recorded. The route SHALL succeed whether or not any presence document exists.

The Steam ID is included so that a client can refuse a deployment recording somebody else's history rather than silently importing it.

#### Scenario: Health reports the configured identity

- **WHEN** an authorized request reaches the health route
- **THEN** the response reports the Steam ID the poller is configured to observe

#### Scenario: Health reports recency separately from observation

- **WHEN** the poller is running and the user has not played for several days
- **THEN** the most recent poll time is recent
- **AND** the most recent observation time is several days old

#### Scenario: Health succeeds on an empty deployment

- **WHEN** the health route is requested and no presence document has ever been written
- **THEN** the route returns successfully with the observation and poll times reported as absent

#### Scenario: Schema version is declared

- **WHEN** the health route responds
- **THEN** the response states the schema version of the documents the gateway serves

### Requirement: History retrieval with coverage

The system SHALL expose a history route accepting a lower time bound and returning both the recorded transitions at or after that bound, in ascending observation order, and the poller's recorded coverage over the same window. Both SHALL be returned by a single request, so that a consumer cannot hold transitions and coverage that describe different windows.

#### Scenario: Transitions are returned in order

- **WHEN** an authorized request supplies a lower bound
- **THEN** the recorded transitions at or after that bound are returned oldest first

#### Scenario: Coverage accompanies transitions

- **WHEN** transitions are returned for a window
- **THEN** the poller's coverage records spanning that same window are returned in the same response

#### Scenario: Unobserved windows are reported as such

- **WHEN** the requested window includes a period during which the poller recorded no coverage
- **THEN** the response's coverage reports that period as uncovered rather than omitting it silently

#### Scenario: Empty window returns an empty result

- **WHEN** the requested window contains no transitions
- **THEN** the route returns successfully with an empty transition list

#### Scenario: Malformed bound is rejected

- **WHEN** the supplied lower bound is absent or not a valid timestamp
- **THEN** the request is refused with a bad-request status

### Requirement: Bounded responses

The system SHALL cap the number of transitions returned by any single history request and SHALL indicate when a response was truncated, supplying the bound a caller must use to request the remainder. A truncated response SHALL NOT be distinguishable from a complete one by length alone.

#### Scenario: Oversized window is truncated explicitly

- **WHEN** a requested window contains more transitions than the cap
- **THEN** the response contains at most the cap
- **AND** the response states that it was truncated and supplies the bound for the next request

#### Scenario: Continuing from a truncated response

- **WHEN** a caller repeats the request using the supplied bound
- **THEN** the following transitions are returned
- **AND** no transition is skipped or repeated across the two responses

#### Scenario: Complete response is marked complete

- **WHEN** a requested window contains fewer transitions than the cap
- **THEN** the response states that it was not truncated

### Requirement: The gateway derives nothing

The system SHALL return recorded document fields only. It SHALL NOT compute or return an interval, a duration, a session, a playtime total, or any other value not present in a stored document. Pairing consecutive transitions into intervals is derivation and belongs to the on-device engine, which remains the sole author of derived values.

#### Scenario: No derived fields are returned

- **WHEN** any route returns transition data
- **THEN** each returned transition carries only fields recorded on the stored document
- **AND** it carries no interval, duration, session, playtime, or experience value

#### Scenario: Adjacent transitions are not merged

- **WHEN** two consecutive transitions are returned
- **THEN** they are returned as two separate records
- **AND** the gateway does not combine them into a single span

### Requirement: Versioned responses

The system SHALL stamp every response with the schema version of the documents it serves, and SHALL serve documents of one schema version only. A stored document whose schema version differs from the served version SHALL be omitted rather than returned as though it matched.

#### Scenario: Responses declare their version

- **WHEN** any route returns successfully
- **THEN** the response states the schema version

#### Scenario: Unrecognized stored version is not served

- **WHEN** a stored document carries a schema version the gateway does not serve
- **THEN** the document is omitted from the response
- **AND** the omission is logged

### Requirement: Colocation with the database

The system SHALL deploy to the same region as the Firestore database.

#### Scenario: Region matches the database

- **WHEN** the gateway is deployed
- **THEN** its region is the region of the Firestore database
