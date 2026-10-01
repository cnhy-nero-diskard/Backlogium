## ADDED Requirements

### Requirement: Explicit historical ranges are bounded and resumable

The authenticated reader SHALL offer an opt-in way to learn the earliest retained observation and to page a player-selected historical range. Without that opt-in, a positionless first read SHALL continue to cover only the bounded recent window. An explicit historical request SHALL be limited to one response page, and the app SHALL bound each user-initiated acquisition batch, preserve completed historical evidence across interruptions, and require another user action to continue a capped batch. It SHALL never apply a partially acquired range. The historical range SHALL have a fixed end captured when acquisition begins, so later poller writes cannot extend the work indefinitely.

#### Scenario: Ordinary first read remains recent
- **WHEN** verification, routine catch-up, placement, or Read now makes a positionless read without historical opt-in
- **THEN** the response covers the existing 31-day recent window, not the entire retained log

#### Scenario: Available beginning is requested
- **WHEN** a configured user asks Settings for the available historical range
- **THEN** an authenticated bounded lookup reports the earliest retained observation for that asserted account, or explicitly reports that none is available
- **AND** an unauthenticated request causes no datastore read

#### Scenario: Historical first page is explicitly selected
- **WHEN** a valid historical start and fixed end are requested without a historical position
- **THEN** the response starts at the chosen effective start, carries at most one page, and provides a position for the next historical page when more remain
- **AND** raw coverage timestamps remain unchanged

#### Scenario: Historical range is interrupted or capped
- **WHEN** a request fails, the process dies, or a user-initiated batch reaches its limit before the fixed end
- **THEN** the completed pages remain resumable for the same account, reader and range
- **AND** no partial historical re-file is applied or presented as complete
- **AND** no further batch starts automatically

#### Scenario: The retained record is empty
- **WHEN** the endpoint has no retained observations
- **THEN** Settings states that there is no available historical range and does not offer a re-file against an invented beginning

### Requirement: Historical acquisition does not steal ordinary read progress

The system SHALL keep historical acquisition state separate from the ordinary read position. Every accepted historical page SHALL retain Steam-owned timing evidence and ingest shared-game evidence through the existing idempotent paths before committing historical progress. It SHALL reject stale account/reader responses, and ordinary reads SHALL keep their existing progress and routine admission rules. Historical evidence SHALL remain isolated from a different account or reader.

#### Scenario: Routine or placement read occurs between historical batches
- **WHEN** an ordinary read runs while a historical acquisition is paused
- **THEN** it resumes its own position without consuming, skipping, or clearing the staged historical range
- **AND** page effects that overlap are not double-credited

#### Scenario: A page fails after acquisition effects
- **WHEN** evidence or ingest persistence fails, or historical progress fails after page effects persist
- **THEN** replay retains or upserts evidence without duplicating derived play and does not skip the failed page

#### Scenario: Account or endpoint changes during acquisition
- **WHEN** the account changes, the reader is removed, or a new endpoint is promoted while an old historical response is in flight
- **THEN** the old response cannot stage evidence or advance progress for the new identity
- **AND** old historical staging cannot be applied to the new identity

### Requirement: Confirmed pre-data presence can date already-imported Steam play

The system SHALL let the player explicitly confirm the end of the period before Backlogium recorded their play, separate from the chosen cloud range start. Only after the independent Steam-history import was opted into MAY a completed, explicitly confirmed historical re-file transfer Steam-owned game minutes from that game's already-counted imported balance into dated History sessions. The transferred amount SHALL be no greater than both the game's remaining imported minutes and its eligible non-overlapping whole minutes inside the selected range and before the confirmed pre-data cutoff, using confirmed cloud coverage or closed original v1 transition spans explicitly disclosed as estimated timing. Unknown, uncovered, or overlapping spans SHALL remain imported and undated. The transfer SHALL NOT increase or decrease the game's Steam-reported lifetime total, the combined imported-plus-session minutes, XP, or level. Its dated sessions SHALL be distinguishable as timing inferred from cloud observations rather than on-device recorded play.

#### Scenario: Confirmed old play with an imported balance
- **WHEN** a player with an already-completed Steam-history import confirms a range and pre-data cutoff containing safe, previously unrecorded observed play for an owned game
- **THEN** the confirmed number of minutes is written as dated History sessions and subtracted in full from that game's imported balance
- **AND** its tracked/session minutes rise by the same amount without changing its combined credited minutes, XP, level, or Steam total

#### Scenario: Original transition-only poller history
- **WHEN** an explicitly confirmed historical read contains a closed interval between two v1 transitions without newer coverage fields
- **THEN** that span MAY date already-imported owned-game minutes as estimated timing under the same range, cutoff, overlap and balance limits
- **AND** missing modern coverage, recorded rejected gaps and unclosed legacy tails remain excluded
- **AND** ordinary reads, shared-game ingest and existing-session placement do not use the legacy estimate

#### Scenario: Transfer uses the whole imported balance
- **WHEN** eligible confirmed play consumes all of a game's remaining imported minutes
- **THEN** the game's tracked/imported distinction remains visible as dated tracked minutes and zero remaining imported minutes, rather than hiding the result of the transfer

#### Scenario: No earlier Steam import
- **WHEN** a player has not opted into importing Steam history
- **THEN** the historical re-file does not mint owned-game sessions from cloud duration or opt the player into the independent import

#### Scenario: Uncovered or already-counted play
- **WHEN** old observations have unknown or rejected coverage, intersect an already-counted session, or exceed the game's imported balance
- **THEN** only confirmed, non-overlapping minutes within that balance MAY become dated sessions
- **AND** the rest remain imported and undated or, when there is no imported balance, remain uncredited

#### Scenario: A cutoff is not inferred from local records
- **WHEN** no trustworthy first-baseline timestamp is stored
- **THEN** the system requires a player-confirmed pre-data cutoff for the transfer rather than treating the first recorded session or an old diagnostic record as that timestamp

#### Scenario: Reversal restores both classifications
- **WHEN** a re-file that transferred imported minutes is reversed after subsequent ordinary Steam syncs
- **THEN** only its created historical sessions are removed, its transferred imported minutes are returned to the same games, and ordinary later sessions stay intact
- **AND** the previously recorded session corrections are reversed as before

#### Scenario: Steam-history reset requires cloud reversal first
- **WHEN** a player requests the independent Steam-history import reset while its imported minutes are still classified as sessions by an applied cloud re-file
- **THEN** the reset does not proceed until the cloud re-file is reversed
- **AND** afterward the existing import-reset action remains available and does not discard ordinary tracked sessions

## MODIFIED Requirements

### Requirement: A cloud read writes nothing derived

The system SHALL NOT write a session, daily progress record, derived gamification value, or progress event as a result of a cloud read, except:

- through the presence session mechanism acting on games whose sessions that mechanism already owns;
- by placing, in time, minutes that playtime diffing has already counted; and
- after a separate, explicit user confirmation and **complete historical acquisition**, by reclassifying already-imported Steam minutes for owned games into safely dated History sessions, with an equal imported-balance reduction and silent derived-state recompute.

A read is an acquisition of recorded fact. Deriving from it is a separate decision with its own provenance and its own author, and SHALL NOT occur solely because a page was fetched. None of these paths makes the cloud the author of owned-game minutes: Steam's diff or the previously imported Steam total authorizes every credited minute, while the cloud may inform only its timing.

#### Scenario: A read of shared-game play
- **WHEN** a cloud read returns intervals naming games whose sessions come from observed presence
- **THEN** sessions may be written for them through that mechanism and its writer

#### Scenario: A read of owned-game play
- **WHEN** a routine, manual, verification, or accuracy-driven read returns intervals naming games whose sessions come from playtime diffing
- **THEN** they may inform where already-counted minutes are placed
- **AND** that read alone SHALL NOT cause any owned-game minute to be added, removed, or created

#### Scenario: A read with no diffed playtime to place
- **WHEN** an ordinary cloud read returns intervals for an owned game and no playtime increase has been observed for it
- **THEN** no session is written for that game by that read

#### Scenario: Complete, explicitly confirmed historical transfer
- **WHEN** every page of a chosen range has been acquired and the player explicitly confirms pre-data dating of already-imported owned-game minutes
- **THEN** only a separate historical application may transfer eligible minutes from the imported balance into dated sessions
- **AND** the session/offset change is all-or-nothing and the total credited minutes do not change

#### Scenario: The reconstruction remains inert
- **WHEN** intervals are reconstructed
- **THEN** the reconstruction itself writes nothing, and any write is performed by the path that consumes it

### Requirement: History can be re-filed once, and reversed

The system SHALL provide a user-initiated action that re-files already-recorded sessions against the completely acquired, explicitly confirmed range of the presence record and, when the player separately confirms a pre-data cutoff and has already imported historical Steam playtime, transfers safely observed pre-data minutes out of that imported balance into dated sessions. It SHALL treat both parts as one one-time event that repeated invocation does not compound, and SHALL let the user reverse both parts, returning attribution and per-game imported balances to what they were except for independent subsequent changes. A different range or cutoff SHALL require reversing the completed re-file first. An interrupted apply SHALL retain the effective range, cutoff and original imported-minute deltas needed to resume the same operation exactly, including its crash-recovery evidence, rather than recomputing replacements from a different range or balance.

The action SHALL disclose that it changes which dates play is credited to — and therefore quests and streaks — and that it does not change experience, levels, or any game's total playtime. If pre-data minutes are transferred, it SHALL disclose that previously imported minutes become tracked/datable sessions without changing the combined credited amount. It SHALL report the selected and effective range, the confirmed cutoff, what was actually covered, and how many minutes were transferred and remain imported, even when no sessions changed.

#### Scenario: Re-filing history
- **WHEN** the user confirms a valid range and every page through its fixed end has been acquired
- **THEN** already-recorded owned-game sessions safely placeable from that range are re-filed onto the dates they were played
- **AND** each game's total recorded minutes are unchanged

#### Scenario: Pre-data History is populated without double-counting
- **WHEN** the independent Steam-history import is present and the player confirms a pre-data cutoff containing eligible cloud-observed owned-game play
- **THEN** safe imported minutes are moved into new dated sessions in History, with equal imported-minutes deductions
- **AND** unaffected imported minutes stay imported and the per-game combined credited minutes do not change

#### Scenario: Repeated invocation does nothing
- **WHEN** the action is invoked again after completing
- **THEN** no further change results and the applied range remains the one reported

#### Scenario: Reversing it
- **WHEN** the user reverses a completed re-filing
- **THEN** original session attribution and transferred imported balances return to their prior classification without removing later ordinary play, and the action is offered again with a new range and cutoff choice

#### Scenario: Changing the range after apply
- **WHEN** a different start date or pre-data cutoff is selected while a prior range remains applied
- **THEN** the different range or cutoff is not applied until the prior re-file is reversed

#### Scenario: Sessions the record cannot speak to
- **WHEN** recorded sessions fall outside the selected effective range or what its presence evidence covers
- **THEN** they are left exactly as they are rather than rewritten on an assumption

#### Scenario: Sessions the record cannot safely place are left unchanged
- **WHEN** a recorded session is covered only by intervals the placement tolerance rejects, so no confirmed span remains to place it onto
- **THEN** it is left exactly as it is rather than re-filed on rejected evidence
- **AND** each game's total recorded minutes are unchanged by leaving it

#### Scenario: The disclosure is accurate
- **WHEN** the action is presented
- **THEN** it states that dates, quests and streaks may change and that experience, levels and totals will not
- **AND** it shows the chosen start, fixed end, confirmed pre-data cutoff if selected, imported-minute limit and effective evidence boundary before confirmation

#### Scenario: Incomplete range changes no historical attribution
- **WHEN** historical acquisition is paused, capped, cancelled, or fails before the fixed end
- **THEN** no one-time re-file is marked applied and no historical session attribution is changed by that re-file

#### Scenario: An interrupted application resumes the same range
- **WHEN** a session/offset ledger commit succeeds but the applied marker is not yet durable
- **THEN** a retry uses the recorded range, cutoff, imported-minute deltas and retained evidence to finish or reverses safely, without re-computing against a different range or current Steam/import state

#### Scenario: Corrections are silent
- **WHEN** re-filing changes a past date's quest outcome or a streak
- **THEN** the change is reflected without a progress event, under the provenance for play observed retroactively

### Requirement: Cursor-advancing reads retain owned-game evidence for a later Steam delta

Every operation that advances the shared cloud read position — successful verification, manual Read now, routine catch-up, and accuracy-driven placement reads — SHALL durably retain reconstructed intervals for Steam-owned games, including their coverage metadata and needed interval boundary state, before advancing past them. Explicit historical acquisition SHALL likewise retain owned-game evidence before advancing its separate historical position. The reads SHALL use the existing serialized sequence. Page effects SHALL be idempotent: if evidence persistence or the shared-game ingest consumer fails, the corresponding page position SHALL remain retryable; if effects persist but position persistence fails, replay SHALL safely upsert evidence and SHALL NOT double-credit ingested play. A later failed Steam baseline/session commit SHALL leave pending evidence available for retry.

The configured reader SHALL have a persisted, monotonically increasing generation. Pending evidence SHALL be bound to the Steam account and reader generation, and SHALL be upserted by account, reader generation, app id, and interval start so page overlap refines rather than duplicates it. A terminal read that emits an ongoing interval SHALL retain its opening transition or equivalent current-state boundary. If a later page contains only the closing transition, reconstruction SHALL use the retained boundary and update that same interval's final end and coverage rather than leaving stale ongoing evidence or losing the close. Retaining evidence is acquisition of timing evidence only: it SHALL NOT write a session, place Steam-counted minutes, or create contribution provenance. When a later Steam delta covers retained evidence, accuracy-driven placement SHALL combine it with newly unread intervals before applying the existing coverage and complete-window rules.

Pending evidence SHALL remain available until the Steam sync commits the corresponding baseline and session actions. After each successful per-app Steam baseline commit, including a successful sync with no positive delta, closed intervals whose end is at or before the new `lastSyncAt` SHALL be pruned because no future diff window can intersect them; intervals extending beyond the baseline and ongoing intervals SHALL remain available. Reader removal, endpoint replacement, account change, and `AccountRoomReset` SHALL delete prior-generation/account pending evidence, and in-flight reads SHALL be fenced from writing after the generation changes.

#### Scenario: Routine catch-up precedes Steam's reported increase
- **WHEN** a routine read consumes an owned-game interval before Steam reports the matching playtime increase
- **THEN** the interval is retained as pending placement evidence before the shared read position advances
- **AND** no session, Steam minute, or session contribution provenance is written by the routine read

#### Scenario: Historical acquisition preserves ordinary position
- **WHEN** an explicit historical page contains an owned-game interval
- **THEN** its pending evidence is retained before historical progress advances
- **AND** the ordinary read position is not reset or moved by the historical page

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
