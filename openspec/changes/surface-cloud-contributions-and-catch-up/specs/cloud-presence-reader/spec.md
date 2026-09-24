## ADDED Requirements

### Requirement: Cloud contributions remain attributable to the sessions they affected

The system SHALL retain, for each session it writes or changes using cloud observations, whether those observations supplied confirmed play for a presence-derived game or informed the timing of Steam-counted minutes for an owned game. It SHALL retain the distinction when a session contains both locally observed and cloud-assisted contributions. A read that merely fetched evidence, configuration of a reader, or an interval that did not affect a session SHALL NOT create contribution provenance. Unknown legacy provenance SHALL remain unknown rather than being inferred after the fact.

#### Scenario: Cloud recovers shared-game play
- **WHEN** accepted cloud observations cause confirmed minutes to be credited to a family-shared game's session
- **THEN** that session records a recovered-play contribution

#### Scenario: Cloud and phone both contribute to one session
- **WHEN** a locally observed session is extended with credited cloud-observed play
- **THEN** the session records that its contribution is partial rather than claiming the whole session was recovered from cloud observations

#### Scenario: Cloud places owned-game play
- **WHEN** a presence-informed placement changes the session actions that would have been written for a Steam-reported increase
- **THEN** the affected sessions record a timing contribution, with Steam's counted minutes unchanged

#### Scenario: Cloud consulted without changing the result
- **WHEN** an interval is fetched but is rejected, unused, or produces no change to the unaided session actions
- **THEN** no new cloud contribution is attributed to that session

#### Scenario: Existing sessions lack evidence
- **WHEN** a pre-existing session has no recorded cloud contribution provenance
- **THEN** its contribution is unknown and the system does not assign one from its timestamps or game source

#### Scenario: Historical changes can be undone
- **WHEN** a historical re-file changes sessions and is then reversed
- **THEN** both the sessions and their contribution provenance return to their pre-re-file state

### Requirement: Configured readers catch up routinely without excessive automatic reads

Once a cloud reader has been successfully configured, the system SHALL offer a default Automatic catch-up policy with a daily background opportunity and a play-end opportunity. It SHALL also offer bounded routine minimum-gap choices of 12 hours, 24 hours, and 48 hours. Automatic SHALL use a 12-hour minimum gap with a daily background opportunity. A chosen minimum gap SHALL apply across the routine background and play-end triggers together; a routine read SHALL NOT start before it is eligible. The chosen interval is a minimum gap, not an exact execution time or a guarantee that a phone without network will catch up.

Manual reads and existing cloud reads needed by the Steam sync or targeted playtime path to place delayed play SHALL remain available independently of that routine minimum gap. A recent successful read by another path SHALL satisfy a pending routine opportunity only if it reached the end of the unread history; a partial page SHALL NOT suppress needed catch-up. The configured preference SHALL NOT alter the cloud poller's own one-minute observation schedule.

#### Scenario: No reader configured
- **WHEN** cloud presence is not configured
- **THEN** no routine cloud read is scheduled or made and no cloud failure is surfaced

#### Scenario: Default policy catches up a quiet phone
- **WHEN** a configured phone has connectivity, has not recently read cloud observations, and no play-end event occurs
- **THEN** a daily background opportunity can read pending observations under Automatic

#### Scenario: Play ends before the next background opportunity
- **WHEN** a play-end event is observed and the selected routine minimum gap has elapsed
- **THEN** the phone requests catch-up without waiting for the next background opportunity

#### Scenario: Triggers arrive together
- **WHEN** a play-end opportunity and a background opportunity arrive during one minimum-gap period
- **THEN** at most one routine catch-up is admitted for that period

#### Scenario: A manual read returns only one of several pages
- **WHEN** a recent manual read succeeded but reports that more unread history remains
- **THEN** the next eligible routine catch-up is not treated as already complete

#### Scenario: A correctness-driven sync read is due during cooldown
- **WHEN** a Steam sync needs cloud evidence to place an eligible delayed playtime increase while routine catch-up is in cooldown
- **THEN** that existing accuracy-driven read remains eligible and Steam sync still completes if the reader fails

#### Scenario: Offline phone
- **WHEN** an opportunity occurs without connectivity
- **THEN** no cloud data is required for the app to work and catch-up waits for a later eligible connected opportunity

#### Scenario: Manual read during cooldown
- **WHEN** the player selects Read now during a routine minimum-gap period
- **THEN** that request remains available and is not refused on account of the routine preference

### Requirement: Routine catch-up reports how far it actually read

The system SHALL resume authenticated, account-bound reads from the existing durable position, consume returned pages through the existing ingest path, and report a catch-up as complete only when no unread page remains. It SHALL bound work per background attempt, preserve an unfinished position for a later eligible attempt, and distinguish a completed read with no new transitions from a partial read or a failure. A successful reader request SHALL NOT be represented as proof that the separate poller has continued to observe Steam; where the latest observed timestamp is available, it SHALL be identified as observation freshness rather than reader success.

#### Scenario: Multiple pages pending
- **WHEN** one page indicates more transitions remain
- **THEN** a routine catch-up continues within its bounded attempt or retains a resumable position and reports a partial catch-up, not complete

#### Scenario: No new transitions
- **WHEN** an authenticated read succeeds with no unread transitions and no further page
- **THEN** the attempt is reported as a completed read with no new transitions

#### Scenario: Account changes or reader is removed
- **WHEN** the configured Steam account changes or the cloud configuration is removed while routine work is pending
- **THEN** that work cannot ingest or present the prior account's observations, and no further routine reads occur without verified configuration

#### Scenario: Reader endpoint is replaced while routine work is pending
- **WHEN** a replacement reader is successfully verified for the active Steam account while work for the previous endpoint is pending or in flight
- **THEN** pending work for the previous endpoint is cancelled and in-flight responses are fenced from ingesting or presenting observations
- **AND** subsequent routine reads use only the verified replacement and remain subject to the existing cadence gate

#### Scenario: Reader succeeds while poller is stale
- **WHEN** the reader succeeds but the newest observation is old
- **THEN** the status distinguishes the successful read from the older observation and does not claim the poller is healthy

### Requirement: Routine reads retain owned-game evidence for a later Steam delta

A routine read SHALL durably retain account-bound intervals for Steam-owned games, including their coverage metadata, before advancing the shared read position past them. Intervals SHALL be upserted by account, app id, and interval start so page overlap does not duplicate evidence. Retaining an interval is acquisition of timing evidence only: it SHALL NOT write a session, place Steam-counted minutes, or create contribution provenance. When a later Steam delta covers retained evidence, accuracy-driven placement SHALL combine that evidence with any newly unread intervals before applying the existing coverage and complete-window rules. Pending evidence SHALL remain available until the Steam sync commits the corresponding baseline and session actions; that commit consumes the evidence so a failed or interrupted sync can retry it. Account changes and reader removal SHALL clear account-bound pending evidence.

#### Scenario: Routine catch-up precedes Steam's reported increase
- **WHEN** a routine read consumes an owned-game interval before Steam reports the matching playtime increase
- **THEN** the interval is retained as pending placement evidence before the shared read position advances
- **AND** no session, Steam minute, or session contribution provenance is written by the routine read

#### Scenario: Placement follows a routine read inside the Steam diff window
- **WHEN** the later accuracy-driven read starts after the routine read advanced the shared cursor into its diff window
- **THEN** placement combines the retained interval with the newly unread suffix
- **AND** it applies the ordinary complete-window and coverage rules to the combined evidence rather than rejecting or placing against the suffix alone
- **AND** the total credited minutes remain exactly the Steam-reported increase

#### Scenario: Placement does not commit
- **WHEN** placement or the Steam sync fails before committing its baseline and session actions
- **THEN** the pending intervals remain available for retry

#### Scenario: Account changes before placement
- **WHEN** the Steam account changes or the reader is removed while owned-game intervals are pending
- **THEN** the old account's intervals are discarded and cannot inform a later placement
