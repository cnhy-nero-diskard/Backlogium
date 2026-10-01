# cloud-presence-reader

## Purpose

Defines how presence recorded in the cloud reaches the device: how the read is authenticated and
bound to one account, how it is windowed and resumed, how transitions become intervals carrying
what is known about their coverage, and the rule that none of it exists until the user configures
it.

## Requirements

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
whole retained history by default. Each returned transition SHALL carry the coverage record phase
1 wrote for it — `prevLastObservedAt` and, where present, the interior-gap pair
`prevCoverageLapseFrom` / `prevCoverageLapseRecoveredAt` — verbatim, without reduction to a
derived verdict.

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

#### Scenario: Coverage fields survive the read
- **WHEN** a stored transition carries a last-confirmed time and an interior-gap pair
- **THEN** the response carries the same `prevLastObservedAt` and the same
  `prevCoverageLapseFrom` / `prevCoverageLapseRecoveredAt` verbatim
- **AND** the pair is not dropped, normalised to a duration, or folded into a derived verdict

### Requirement: Transitions reconstruct into intervals with stated coverage
The system SHALL turn a sequence of transitions, together with the current state, into ordered
intervals of what was being played. Every interval SHALL carry what is known about whether it was
observed throughout: observed continuously, observed only until a stated time, or unknown. Every
interval SHALL additionally preserve the raw interior-gap pair (`prevCoverageLapseFrom` /
`prevCoverageLapseRecoveredAt`) from the transition that closed it, verbatim and alongside that
tail coverage, whenever the transition carries one. The three-state value summarises the tail; it
SHALL NOT replace or absorb the interior pair, and the reconstruction SHALL apply no tolerance to
decide whether the retained span counts as a lapse — that verdict belongs to the consumer.

An interval whose coverage is unknown SHALL NOT be presented or treated as observed continuously.
An interval carrying an interior-gap pair SHALL NOT be presented or treated as observed
continuously across that span, even when its tail is fresh.

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

#### Scenario: Interior-gap evidence is preserved
- **WHEN** a transition carries an interior-gap pair alongside its last-confirmed time
- **THEN** the interval it closes carries that same `prevCoverageLapseFrom` /
  `prevCoverageLapseRecoveredAt` pair verbatim, alongside its tail coverage
- **AND** the pair is not dropped, normalised to a duration, or folded into the three-state value

#### Scenario: A fresh tail with an interior gap is not continuous
- **WHEN** a v3 transition closing an interval that began at `t0` carries a last-confirmed time
  adjacent to its own timestamp together with an interior-gap pair for an earlier span (for
  example `prevLastObservedAt=t10` with `prevCoverageLapseFrom=t1` /
  `prevCoverageLapseRecoveredAt=t10` on a transition at `t11`)
- **THEN** the interval is NOT marked as observed continuously
- **AND** it carries the interior pair through to its consumers, so a downstream reader still
  sees the `t1..t10` span as unobserved rather than reading `t10` against `t11` as continuous

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
progress event as a result of a cloud read, except:

- through the presence session mechanism acting on games whose sessions that mechanism already
  owns;
- by placing, in time, minutes that playtime diffing has already counted; and
- after a separate, explicit user confirmation and **complete historical acquisition**, by
  reclassifying already-imported Steam minutes for owned games into safely dated History sessions,
  with an equal imported-balance reduction and silent derived-state recompute.

A read is an acquisition of recorded fact. Deriving from it is a separate decision with its own
provenance and its own author, and SHALL NOT occur solely because a page was fetched. None of these
paths makes the cloud the author of owned-game minutes: Steam's diff or the previously imported
Steam total authorizes every credited minute, while the cloud may inform only its timing.

#### Scenario: A read of shared-game play

- **WHEN** a cloud read returns intervals naming games whose sessions come from observed presence
- **THEN** sessions may be written for them through that mechanism and its writer

#### Scenario: A read of owned-game play
- **WHEN** a routine, manual, verification, or accuracy-driven read returns intervals naming games whose sessions come from playtime diffing
- **THEN** they may inform where already-counted minutes are placed
- **AND** that read alone SHALL NOT cause any owned-game minute to be added, removed, or created

#### Scenario: A read with no diffed playtime to place
- **WHEN** an ordinary cloud read returns intervals for an owned game and no playtime increase has been observed for it
- **THEN** no session is written for that game

#### Scenario: Complete, explicitly confirmed historical transfer
- **WHEN** every page of a chosen range has been acquired and the player explicitly confirms pre-data dating of already-imported owned-game minutes
- **THEN** only a separate historical application may transfer eligible minutes from the imported balance into dated sessions
- **AND** the session/offset change is all-or-nothing and the total credited minutes do not change

#### Scenario: The reconstruction remains inert

- **WHEN** intervals are reconstructed
- **THEN** the reconstruction itself writes nothing, and any write is performed by the path that
  consumes it

### Requirement: Observed intervals place diffed minutes without changing their total

Where a playtime increase is observed for a game and a record of observed presence covers the
period it was earned in, the system SHALL distribute those minutes across the observed intervals
rather than attributing them all to a single estimated start. The sum of the minutes placed SHALL
equal the observed increase exactly.

The system SHALL NOT place minutes into a period no interval covers, and SHALL NOT place more
minutes than were observed as played.

Steam's cumulative total is the only authority on how much was played; the presence record is the
only authority on when. Keeping those roles separate is what makes this safe — a wrong or
incomplete presence record can misplace minutes but can never invent them, because the total it is
distributing was not derived from it.

#### Scenario: One increase across several observed intervals

- **WHEN** a playtime increase is observed and the presence record shows several separate intervals
  of that game within the period
- **THEN** a session is written for each interval
- **AND** the minutes across them sum to the observed increase

#### Scenario: The total is never changed

- **WHEN** minutes are placed across intervals whose spans total more or less than the observed
  increase
- **THEN** the minutes written still sum to the observed increase

#### Scenario: Placement respects the attribution rule

- **WHEN** placed sessions begin on different calendar dates
- **THEN** each session's minutes are credited to the date that session began
- **AND** no session's minutes are divided across dates

#### Scenario: No interval covers the period

- **WHEN** a playtime increase is observed and the presence record shows no interval for that game
- **THEN** the minutes are attributed as they would be without any presence record

#### Scenario: A game still in progress

- **WHEN** the final interval is still ongoing at the time of the observation
- **THEN** the session placed for it remains open and is extended by later observations

#### Scenario: Unconfirmed time receives no minutes

- **WHEN** an interval was confirmed only until a time before it ended
- **THEN** minutes are placed only against the confirmed portion

#### Scenario: An interior gap exceeding tolerance removes the interval from placement

- **WHEN** an interval carries an interior-gap pair alongside a fresh tail (for example a v3
  transition carrying `prevLastObservedAt=t10` with `prevCoverageLapseFrom=t1` /
  `prevCoverageLapseRecoveredAt=t10`) and the span between that pair exceeds the placement
  rule's own tolerance
- **THEN** the interval contributes no confirmed span, rather than contributing its portions
  outside the `t1..t10` span
- **AND** placement allocates nothing from that interval, including from its fresh tail,
  because phase 1 retains only the largest step and a second outage above the same tolerance
  may have been discarded
- **AND** where that leaves no confirmed span to distribute across, no placement is returned
  and the increase is credited by the unaided attribution instead; that fallback retains the
  old estimate and is not claimed to avoid the unobserved span

#### Scenario: Two interior outages make the interval unusable for placement

- **WHEN** one state observed `A@t0 → A@t1 → outage → A@t10 → A@t11 → outage → A@t18 → B@t19`
  and the retained pair records only the largest step, with both interior outages above the
  placement tolerance
- **THEN** the interval contributes no confirmed span, so placement allocates nothing from it,
  including nothing located by the retained pair
- **AND** where it is the only covering interval, no placement is returned and the increase is
  still credited in full by the unaided attribution; that fallback is not claimed to avoid
  either outage span

### Requirement: Placement never degrades the estimate it replaces

The system SHALL fall back to attributing minutes as it would without any presence record whenever
the record cannot improve on it — including when no record covers the period, when the record
is unavailable, when coverage is unknown, when every covering interval is rejected by the gap
tolerance and therefore contributes no confirmed span, and when the period is short enough that
the existing estimate is already within the record's own resolution.

A rejected or gapped presence interval makes the record unusable for placement: the old estimate
is retained rather than placing minutes on evidence deliberately rejected. That fallback preserves
Steam's total but is not claimed to avoid the unobserved spans.

No configuration of the presence record SHALL cause a game's minutes to go uncredited.

#### Scenario: Record unavailable

- **WHEN** the presence record cannot be read during a sync
- **THEN** the sync attributes minutes exactly as it does without the feature, and completes

#### Scenario: Feature not configured

- **WHEN** no cloud endpoint is configured
- **THEN** session synthesis behaves exactly as it did before this capability existed

#### Scenario: A short period needs no correction

- **WHEN** the period since the previous observation is short enough that the existing estimate is
  already as precise as the record
- **THEN** the record is not consulted for that period

#### Scenario: Minutes are never lost

- **WHEN** placement is attempted and cannot be completed for any reason
- **THEN** the observed increase is still credited in full by the unaided attribution

#### Scenario: Every covering interval rejected retains the old estimate

- **WHEN** a playtime increase is observed and every presence interval covering its period is
  rejected by the gap tolerance, so the confirmed span totals zero
- **THEN** no placement is returned and the increase is credited in full as a single session
  exactly as it would be without any presence record
- **AND** no claim is made that the resulting session avoids the unobserved spans; the record
  was unusable for placement, so the old estimate is retained with its total intact

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

- **WHEN** recorded sessions fall outside what the presence record covers
- **THEN** they are left exactly as they are rather than rewritten on an assumption

#### Scenario: Sessions the record cannot safely place are left unchanged

- **WHEN** a recorded session is covered only by intervals the placement tolerance rejects,
  so no confirmed span remains to place it onto
- **THEN** it is left exactly as it is rather than re-filed on rejected evidence
- **AND** each game's total recorded minutes are unchanged by leaving it

#### Scenario: The disclosure is accurate
- **WHEN** the action is presented
- **THEN** it states that dates, quests and streaks may change and that experience, levels and
  totals will not
- **AND** it shows the chosen start, fixed end, confirmed pre-data cutoff if selected, imported-minute limit and effective evidence boundary before confirmation

#### Scenario: Incomplete range changes no historical attribution
- **WHEN** historical acquisition is paused, capped, cancelled, or fails before the fixed end
- **THEN** no one-time re-file is marked applied and no historical session attribution is changed by that re-file

#### Scenario: An interrupted application resumes the same range
- **WHEN** a session/offset ledger commit succeeds but the applied marker is not yet durable
- **THEN** a retry uses the recorded range, cutoff, imported-minute deltas and retained evidence to finish or reverses safely, without re-computing against a different range or current Steam/import state

#### Scenario: Corrections are silent

- **WHEN** re-filing changes a past date's quest outcome or a streak
- **THEN** the change is reflected without a progress event, under the provenance for play observed
  retroactively

### Requirement: Cloud intervals are ingested only for presence-derived games

The system SHALL admit a cloud interval to session derivation only where the named game's stored
source is one whose sessions are derived from observed presence. An interval naming a game whose
sessions come from playtime diffing SHALL be discarded, exactly as an unrecognised game is.

Two independent detectors produce session boundaries that disagree and cannot be deduplicated.
This is the same partition the on-device observer already honours, enforced the same way — by what
the ingest is handed, not by what it chooses.

#### Scenario: A family-shared game

- **WHEN** a cloud interval names a game whose stored source is family-shared
- **THEN** it is admitted to derivation

#### Scenario: An owned game

- **WHEN** a cloud interval names a game whose stored source is Steam-owned
- **THEN** it is discarded, and no session is opened, extended or closed for it

#### Scenario: An unknown game

- **WHEN** a cloud interval names a game not present in the library
- **THEN** it is discarded rather than admitted speculatively

#### Scenario: Mixed intervals in one read

- **WHEN** a read returns intervals for both owned and family-shared games
- **THEN** only the family-shared ones reach derivation, and the presence of the others changes
  nothing about how they are handled

### Requirement: Ingested play is not counted twice

The system SHALL record how far cloud observations have been ingested and SHALL NOT derive a second
session from an interval already accounted for. Where the on-device observer has already recorded
play for a stretch the cloud also observed, the result SHALL be one session's worth of credited
time, not two.

#### Scenario: Re-running an ingest

- **WHEN** the same cloud window is ingested twice
- **THEN** the second ingest credits no additional minutes and creates no additional session

#### Scenario: Play observed by both the device and the cloud

- **WHEN** the on-device observer recorded a session and the cloud observed the same stretch
- **THEN** the credited time reflects that play once

#### Scenario: Ingest position survives the process

- **WHEN** the app is killed partway through an ingest
- **THEN** the next ingest resumes without re-crediting what was already written

#### Scenario: Position discarded on an account change

- **WHEN** the configured Steam account changes
- **THEN** the ingest position is discarded along with the read position

### Requirement: Unconfirmed time is not credited to a shared game

Where a cloud interval's coverage is partial or unknown, the system SHALL credit only the span
that was confirmed by observation, and SHALL NOT credit the unobserved remainder. Where an
interval carries the raw interior-gap pair phase 2 preserves (`prevCoverageLapseFrom` /
`prevCoverageLapseRecoveredAt`), the system SHALL apply its own tolerance to the raw pair
rather than a verdict computed upstream, and SHALL treat the whole interval as unconfirmed —
crediting none of it — once that retained span exceeds its tolerance. Phase 1 retains only
the largest consecutive-observation step, so the retained span preserves the verdict but not
the locations: a second interior step above the same tolerance may have been discarded, and
excluding only the retained span would still credit that hidden outage.

A shared game has no Steam-reported total, so nothing external bounds an over-credit — an interval
mistakenly credited in full becomes that game's tracked time with no correction available from any
source. An owned game's equivalent error is caught by its lifetime total; this one is not.

#### Scenario: Interval observed continuously

- **WHEN** an interval is recorded as observed throughout
- **THEN** its full span is available for derivation

#### Scenario: Interval observed only until a stated time

- **WHEN** an interval was confirmed only until a time before the transition that closed it
- **THEN** derivation uses the confirmed portion and the remainder is not credited

#### Scenario: Interval with an interior gap exceeding tolerance is discarded

- **WHEN** an interval carries an interior-gap pair alongside a fresh tail (for example a v3
  transition carrying `prevLastObservedAt=t10` with `prevCoverageLapseFrom=t1` /
  `prevCoverageLapseRecoveredAt=t10`) and the span between that pair exceeds the ingest's
  own tolerance
- **THEN** no span of the interval is credited, rather than only the `t1..t10` span being
  excluded while the remainder is kept
- **AND** the interval is not treated as continuous on the strength of its fresh tail

#### Scenario: Interval with two interior outages credits no unobserved span

- **WHEN** one state observed `A@t0 → A@t1 → outage → A@t10 → A@t11 → outage → A@t18 → B@t19`
  and the retained pair records only the largest step, with both interior outages above the
  ingest's tolerance
- **THEN** no span of the interval is credited, so the outage the retained pair does not
  locate is not credited either

#### Scenario: Interval of unknown coverage

- **WHEN** an interval carries no coverage record
- **THEN** it is treated conservatively rather than as observed throughout

#### Scenario: Play entirely inside a gap is not invented

- **WHEN** a game was played entirely during a period the poller did not observe
- **THEN** no session is derived for it, and no span is inferred from surrounding intervals

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

Once a cloud reader has been successfully configured, the system SHALL offer a default Automatic catch-up policy with a daily background opportunity and a play-end opportunity. It SHALL also offer bounded routine minimum-gap choices of 12 hours, 24 hours, and 48 hours, plus a persisted Off / Manual only policy distinct from an absent, uninitialized preference. Automatic SHALL use a 12-hour minimum gap with a daily background opportunity. A chosen minimum gap SHALL apply across the routine background and play-end triggers together; a routine read SHALL NOT start before it is eligible. While Off / Manual only is selected, neither trigger SHALL schedule or admit a new routine read, pending routine work SHALL be cancelled, and an in-flight routine read SHALL stop safely without rolling back already committed page effects. Disabling or re-enabling routine reads SHALL NOT clear the last admitted attempt, read cursor, reader configuration, or ingest position; re-enabling SHALL use the existing admission cooldown rather than create an immediate burst. A verified endpoint replacement for the same account SHALL preserve an explicit Off selection. Only a truly uninitialized policy MAY be initialized to Automatic on first verification or upgrade. The chosen interval is a minimum gap, not an exact execution time or a guarantee that a phone without network will catch up.

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

#### Scenario: Reader stays connected without routine catch-up
- **WHEN** the player selects Off / Manual only
- **THEN** periodic and play-end routine opportunities do not enqueue or admit a new routine read, pending routine work is cancelled, and the connected reader remains available for manual and accuracy-driven reads
- **AND** an already in-flight routine attempt stops at a safe boundary without discarding committed page effects

#### Scenario: Routine catch-up is re-enabled
- **WHEN** the player selects an enabled cadence after Off / Manual only
- **THEN** routine opportunities resume under the selected cadence and persisted last-admission cooldown, without a request burst or loss of cursor or ingest state

#### Scenario: Explicit Off is not an uninitialized policy
- **WHEN** an app restarts, upgrades, or replaces a verified endpoint for the same account with Off / Manual only saved
- **THEN** reconciliation preserves that selection instead of initializing Automatic

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

Explicit historical acquisition SHALL durably retain owned-game timing evidence before advancing its separate historical position; it SHALL NOT reset or move the ordinary read position.

Every operation that advances the shared cloud read position — successful verification, manual Read now, routine catch-up, accuracy-driven placement reads, and the complete-history drain for historical re-file — SHALL durably retain reconstructed intervals for Steam-owned games, including their coverage metadata and needed interval boundary state, before advancing past them. The reads SHALL use the existing serialized sequence. Page effects SHALL be idempotent: if evidence persistence or the shared-game ingest consumer fails, the page position SHALL remain retryable; if effects persist but position persistence fails, replay SHALL safely upsert evidence and SHALL NOT double-credit ingested play. A later failed Steam baseline/session commit SHALL leave pending evidence available for retry.

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
