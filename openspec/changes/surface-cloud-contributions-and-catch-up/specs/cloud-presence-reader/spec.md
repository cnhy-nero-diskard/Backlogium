## ADDED Requirements

### Requirement: Cloud contributions remain attributable to the sessions they affected

The system SHALL retain, for each session it writes or changes using cloud observations, two independent facts: whether those observations supplied confirmed play for a presence-derived game, and whether they informed the timing of Steam-counted minutes for an owned game. Each fact SHALL independently record `UNKNOWN`, `NONE`, `FULL`, or `PARTIAL`; both contribution facts MAY be present on one session, and partiality SHALL apply to the relevant fact only. A read that merely fetched evidence, configuration of a reader, or an interval that did not affect a session SHALL NOT create contribution provenance. Unknown legacy provenance SHALL remain unknown rather than being inferred after the fact.

#### Scenario: Cloud recovers shared-game play
- **WHEN** accepted cloud observations cause confirmed minutes to be credited to a family-shared game's session
- **THEN** that session records a recovered-play contribution

#### Scenario: Cloud and phone both contribute to one session
- **WHEN** a locally observed session is extended with credited cloud-observed play
- **THEN** the session records that its contribution is partial rather than claiming the whole session was recovered from cloud observations

#### Scenario: Cloud places owned-game play
- **WHEN** a presence-informed placement changes the session actions that would have been written for a Steam-reported increase
- **THEN** the affected sessions record a timing contribution, with Steam's counted minutes unchanged

#### Scenario: Historical re-file adds timing provenance
- **WHEN** accepted cloud intervals cause a historical re-file to change the timing of owned-game replacement sessions
- **THEN** each changed replacement records a timing-informed fact without clearing any recovered-play or prior timing fact
- **AND** reversing the re-file restores the exact prior session rows and both prior fact states

#### Scenario: One session carries both contribution facts
- **WHEN** source conversion, restore, or later writes extend a session that already records one contribution fact using the other cloud path
- **THEN** the session retains both the recovered-play and timing-informed facts with independent full/partial states

#### Scenario: Cloud consulted without changing the result
- **WHEN** an interval is fetched but is rejected, unused, or produces no change to the unaided session actions
- **THEN** no new cloud contribution is attributed to that session

#### Scenario: Existing sessions lack evidence
- **WHEN** a session row predates provenance storage and its migrated fact fields are null
- **THEN** its contribution is unknown and the system does not assign one from its timestamps or game source

#### Scenario: New session has no cloud contribution
- **WHEN** a session is written without cloud observations affecting it
- **THEN** both contribution facts are recorded as `NONE`, distinct from unknown legacy provenance

#### Scenario: Historical changes can be undone
- **WHEN** a historical re-file changes sessions and is then reversed
- **THEN** both the sessions and their contribution provenance return to their pre-re-file state

### Requirement: Configured readers catch up routinely without excessive automatic reads

Once a cloud reader has been successfully configured, the system SHALL offer a default Automatic catch-up policy with a daily background opportunity and a play-end opportunity. It SHALL also offer bounded routine minimum-gap choices of 12 hours, 24 hours, and 48 hours. Automatic SHALL use a 12-hour minimum gap with a daily background opportunity. A chosen minimum gap SHALL apply across the routine background and play-end triggers together; a routine read SHALL NOT start before it is eligible. The chosen interval is a minimum gap, not an exact execution time or a guarantee that a phone without network will catch up.

Manual reads and existing cloud reads needed by the Steam sync or targeted playtime path to place delayed play SHALL remain available independently of that routine minimum gap. The system SHALL use persisted, monotonically ordered watermarks for routine admissions and successful reads by other paths; freshness SHALL NOT be inferred from an elapsed-time window. The latest successful read by another path SHALL satisfy at most the next pending routine opportunity only when it reached the end of unread history and its terminal-completion watermark is strictly later than the watermark of the latest admitted routine attempt (or the initial reader-verification watermark before any routine attempt). Once used to satisfy an opportunity, that completion watermark SHALL be consumed so the same read cannot suppress a later opportunity. A successful read completed at or before the latest routine-admission watermark, or a latest successful read that left unread history, SHALL NOT suppress catch-up. The configured preference SHALL NOT alter the cloud poller's own one-minute observation schedule.

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

#### Scenario: A newer terminal read satisfies one routine opportunity
- **WHEN** the latest successful manual or placement read completes after the latest admitted routine attempt and reaches the end of unread history
- **THEN** the next eligible routine opportunity is satisfied without another routine read
- **AND** that read completion cannot satisfy a later routine opportunity again

#### Scenario: An older terminal read does not suppress catch-up
- **WHEN** the latest successful manual or placement read reached the end of unread history but completed at or before the latest admitted routine attempt
- **THEN** the next eligible routine opportunity remains eligible for catch-up

#### Scenario: A partial read does not suppress catch-up
- **WHEN** the latest successful manual or placement read completes after the latest admitted routine attempt but reports that more unread history remains
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

#### Scenario: Existing configured reader is reconciled after upgrade
- **WHEN** an app starts or upgrades with a previously verified reader but no persisted routine policy
- **THEN** Automatic is initialized and the next opportunity is scheduled under the ordinary gate without requiring a new verification event
- **AND** the initial verification-order watermark is seeded for admission comparisons
- **AND** the existing read and ingest positions are preserved

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
- **THEN** that work cannot ingest or present the prior account's observations, pending evidence is deleted, and no further routine reads occur without verified configuration

#### Scenario: Reader endpoint is replaced while routine work is pending
- **WHEN** a replacement reader is successfully verified for the active Steam account while work for the previous endpoint is pending or in flight
- **THEN** pending work for the previous endpoint is cancelled and in-flight responses are fenced from ingesting or presenting observations
- **AND** subsequent routine reads use only the verified replacement and remain subject to the existing cadence gate
- **AND** pending evidence from the previous reader generation is deleted and cannot inform placement from the replacement

#### Scenario: Reader succeeds while poller is stale
- **WHEN** the reader succeeds but the newest observation is old
- **THEN** the status distinguishes the successful read from the older observation and does not claim the poller is healthy

### Requirement: Cursor-advancing reads retain owned-game evidence for a later Steam delta

Every operation that advances the shared cloud read position — successful verification, manual Read now, routine catch-up, accuracy-driven placement reads, and the complete-history drain for historical re-file — SHALL durably retain reconstructed intervals for Steam-owned games, including their coverage metadata and needed interval boundary state, before advancing past them. The reads SHALL use the existing serialized sequence. Page effects SHALL be idempotent: if evidence persistence or the shared-game ingest consumer fails, the page position SHALL remain retryable; if effects persist but position persistence fails, replay SHALL safely upsert evidence and SHALL NOT double-credit ingested play. A later failed Steam baseline/session commit SHALL leave pending evidence available for retry.

The configured reader SHALL have a persisted, monotonically increasing generation. Pending evidence SHALL be bound to the Steam account and reader generation, and SHALL be upserted by account, reader generation, app id, and interval start so page overlap refines rather than duplicates it. A terminal read that emits an ongoing interval SHALL retain its opening transition or equivalent current-state boundary. If a later page contains only the closing transition, reconstruction SHALL use the retained boundary and update that same interval's final end and coverage rather than leaving stale ongoing evidence or losing the close. Retaining evidence is acquisition of timing evidence only: it SHALL NOT write a session, place Steam-counted minutes, or create contribution provenance. When a later Steam delta covers retained evidence, accuracy-driven placement SHALL combine it with newly unread intervals before applying the existing coverage and complete-window rules.

Pending evidence SHALL remain available until the Steam sync commits the corresponding baseline and session actions. After each successful per-app Steam baseline commit, including a successful sync with no positive delta, closed intervals whose end is at or before the new `lastSyncAt` SHALL be pruned because no future diff window can intersect them; intervals extending beyond the baseline and ongoing intervals SHALL remain available. Reader removal, endpoint replacement, account change, and `AccountRoomReset` SHALL delete prior-generation/account pending evidence, and in-flight reads SHALL be fenced from writing after the generation changes.

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

#### Scenario: A placement read is retried after a failed Steam commit
- **WHEN** a placement read persists an owned interval and advances the shared cursor, but the Steam baseline/session commit fails
- **THEN** the retry can reuse that interval and consumes or prunes it only after a successful baseline commit makes it no longer applicable

#### Scenario: A terminal open interval is closed on a later read
- **WHEN** a terminal read persists an ongoing interval and a later read contains only its closing transition
- **THEN** the retained opening boundary reconstructs the same interval with its final end and coverage
- **AND** two successive Steam deltas use only the evidence intersecting their respective diff windows without losing or double-counting minutes

#### Scenario: A no-delta Steam baseline makes old evidence terminal
- **WHEN** a successful Steam sync advances an app's `lastSyncAt` without a positive playtime delta
- **THEN** closed pending intervals ending at or before that baseline are pruned, while intervals extending beyond it remain available

#### Scenario: Account changes before placement
- **WHEN** the Steam account changes or the reader is removed while owned-game intervals are pending
- **THEN** the old account's intervals are discarded and cannot inform a later placement

## MODIFIED Requirements

### Requirement: A recovered session is an ordinary session

A session derived from or changed using cloud observations SHALL use the same session ledger and
shared writer as a session derived on-device, and SHALL participate identically in session
identity, credited minutes, XP, quests, streaks, and analytics. The additive cloud-contribution
provenance required above is an intentional exception to the same-stored-shape and downstream-
indistinguishability guarantees: History SHALL expose it only as attribution explaining the
cloud-assisted contribution. Provenance SHALL NOT create a separate session type or alter session,
play, or progression calculations.

#### Scenario: Downstream treatment
- **WHEN** a session derived from or changed using cloud observations is examined by any consumer
- **THEN** it participates identically to a session derived on-device, with its provenance available
  for the History attribution

#### Scenario: Written through the shared writer
- **WHEN** cloud-derived or cloud-modified sessions are persisted
- **THEN** they are written through the same writer other session mechanisms use, not a parallel
  path
