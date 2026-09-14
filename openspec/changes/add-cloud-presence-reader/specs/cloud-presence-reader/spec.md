## Purpose

Defines how presence recorded in the cloud reaches the device: how the read is authenticated and
bound to one account, how it is windowed and resumed, how transitions become intervals carrying
what is known about their coverage, and the rule that none of it exists until the user configures
it.

## ADDED Requirements

### Requirement: The feature is absent until configured

The system SHALL perform no cloud read, present no cloud-derived state, and report no cloud error
unless the user has configured and successfully verified an endpoint. An unconfigured app SHALL
behave identically to one built before this capability existed.

The app is offline-first and the cloud is additive. A feature that degrades an unconfigured app —
by erroring, by occupying a surface, or by delaying a sync — has made the cloud a dependency
rather than an addition.

#### Scenario: Unconfigured app performs no read

- **WHEN** no endpoint has been configured
- **THEN** no network request is made to any cloud endpoint
- **AND** no surface presents cloud state, an error, or an empty placeholder

#### Scenario: Configuration is removable

- **WHEN** the user clears the configuration
- **THEN** the stored credential is destroyed and the app returns to behaving as if never
  configured

#### Scenario: An unreachable endpoint does not degrade the app

- **WHEN** the endpoint is configured but unreachable, times out, or rejects the credential
- **THEN** every other function of the app proceeds unchanged
- **AND** the failure is recorded as a diagnostic rather than presented as an interruption

### Requirement: Reads are authenticated and rejected cheaply

The read endpoint SHALL require a bearer credential held in the server's secret store, and SHALL
reject a request carrying an absent, malformed, or non-matching credential before performing any
datastore read. A missing server-side credential SHALL cause every request to be rejected, never
admitted.

The endpoint is publicly addressable on a metered project. Rejecting before the datastore read is
what keeps an unauthenticated flood a nuisance rather than a bill, and failing closed on a
misconfigured secret is what stops a deployment mistake from publishing the log.

#### Scenario: Request without a valid credential

- **WHEN** a request arrives with no bearer credential, a malformed one, or one that does not match
- **THEN** it is rejected
- **AND** no presence document is read in serving it

#### Scenario: Server credential is absent

- **WHEN** the endpoint is deployed without its credential configured
- **THEN** every request is rejected
- **AND** no request is served as though unauthenticated access were permitted

#### Scenario: Concurrency is bounded

- **WHEN** requests arrive faster than they can be served
- **THEN** the number of concurrent instances is capped by configuration

### Requirement: The read is bound to one asserted account

The response SHALL state which Steam account it describes. The system SHALL compare that against
the account the app is configured for, and SHALL refuse the response when they differ, rather than
reconciling, merging, or adopting the returned account.

Presence from another account is indistinguishable from the user's own once ingested, and would
fabricate play under their identity. `steam-sync` already refuses to diff a baseline across an
account change for the same reason; a cloud read is the same hazard arriving by a different route.

#### Scenario: Account matches

- **WHEN** the response states the account the app is configured for
- **THEN** the response is accepted

#### Scenario: Account differs

- **WHEN** the response states a different account
- **THEN** the response is discarded in full
- **AND** no part of it reaches any surface or store
- **AND** the mismatch is recorded and surfaced as a configuration fault to be corrected

#### Scenario: The app's account changes

- **WHEN** the configured Steam account is changed
- **THEN** any stored read position is discarded, so a position established under one account is
  never resumed against another

### Requirement: Reads are windowed and resumable

A read SHALL accept a position and return only observations after it, together with a position for
the next read. The system SHALL persist the position it has consumed. A read SHALL NOT return the
whole retained history by default.

Retention is indefinite by design, so an unwindowed read grows without bound for the life of the
project. The stored position is also what makes repeated reads idempotent: re-reading the same
window is harmless because it yields what has already been consumed.

#### Scenario: First read after configuration

- **WHEN** no position has been stored
- **THEN** the read covers a bounded recent window rather than all retained history

#### Scenario: Subsequent read resumes

- **WHEN** a position has been stored
- **THEN** the read returns only observations after it

#### Scenario: A window larger than one response

- **WHEN** more observations exist than one response carries
- **THEN** the response reports that more remain and carries a position from which to continue

#### Scenario: Repeating a read changes nothing

- **WHEN** the same window is read twice
- **THEN** the second read produces the same reconstruction and no additional stored effect

### Requirement: Transitions reconstruct into intervals with stated coverage

The system SHALL turn a sequence of transitions, together with the current state, into ordered
intervals of what was being played. Every interval SHALL carry what is known about whether it was
observed throughout: observed continuously, observed only until a stated time, or unknown.

An interval whose coverage is unknown SHALL NOT be presented or treated as observed continuously.

Coverage is what phase 1 records. Reconstruction that discards it produces a timeline that looks
authoritative and is not, which is worse than one that states its own limits.

#### Scenario: Adjacent transitions form an interval

- **WHEN** a transition to a game is followed by a transition to a different game or to no game
- **THEN** one interval is produced spanning between them, attributed to the first game

#### Scenario: The final interval is still open

- **WHEN** the last transition reports a game and the current state still reports it
- **THEN** the final interval is produced as ongoing, bounded by the latest successful observation
  rather than by a fabricated end

#### Scenario: Coverage is carried through

- **WHEN** a transition records that the state it replaced was last confirmed before the transition
  occurred
- **THEN** the interval it closes is marked as observed only until that confirmed time

#### Scenario: A transition carrying no coverage

- **WHEN** a transition carries no record of the replaced state's last confirmed observation
- **THEN** the interval it closes is marked unknown
- **AND** it is not marked as observed continuously

#### Scenario: Uncertainty propagates to the following interval

- **WHEN** an interval is observed only until a stated time before the transition that closed it
- **THEN** the interval beginning at that transition is marked as possibly having begun earlier
  than its recorded start

#### Scenario: Reconstruction derives no playtime

- **WHEN** intervals are reconstructed
- **THEN** no session, playtime total, experience, streak, or daily progress value is produced

### Requirement: A cloud read writes nothing derived

The system SHALL NOT write a session, daily progress record, derived gamification value, or
progress event as a result of a cloud read.

A read is an acquisition of recorded fact. Deriving from it is a separate decision with its own
provenance and its own author, and must be introduced deliberately rather than arriving as a side
effect of fetching.

#### Scenario: A read completes

- **WHEN** a cloud read succeeds and intervals are reconstructed
- **THEN** the session ledger, daily progress, and all derived values are unchanged
- **AND** no progress event is produced

#### Scenario: Only the read position is persisted

- **WHEN** a cloud read succeeds
- **THEN** the only durable effect is the stored read position and the recorded diagnostic

### Requirement: Hidden games are resolved before presentation

Where a reconstructed interval names a game the user has hidden, the system SHALL resolve it the
same way live presence is resolved, so no surface derived from a cloud read names or depicts a
hidden game.

Hiding is enforced at one resolution point precisely so each surface does not re-implement it. A
cloud read is a new source reaching the same surfaces, and must pass the same point rather than
around it.

#### Scenario: An interval names a hidden game

- **WHEN** a reconstructed interval names a game that is hidden
- **THEN** no surface presenting that reconstruction names or depicts it

#### Scenario: Unhiding restores it

- **WHEN** a hidden game is unhidden
- **THEN** intervals naming it are presented normally, without a re-read being required to recover
  them

### Requirement: The cloud credential is stored encrypted and never disclosed

The system SHALL store the endpoint credential encrypted at rest under an Android Keystore-backed
key, SHALL mask it wherever it is displayed, and SHALL NOT write it to any log sink. The endpoint
SHALL NOT write the configured account or any observed title to its operational log output.

#### Scenario: Credential stored encrypted

- **WHEN** the credential is saved
- **THEN** its persisted representation is ciphertext produced with a Keystore-backed key

#### Scenario: Credential displayed

- **WHEN** the configured credential is shown in a settings surface
- **THEN** it is masked

#### Scenario: Credential never logged

- **WHEN** a cloud read succeeds or fails, on any path
- **THEN** no log entry contains the credential

#### Scenario: The endpoint's own logs carry no identity

- **WHEN** the read endpoint emits any log entry
- **THEN** it contains neither the configured Steam ID nor any observed game's app id or name
