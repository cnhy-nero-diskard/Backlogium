# cloud-presence-poller

## ADDED Requirements

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

## MODIFIED Requirements

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
