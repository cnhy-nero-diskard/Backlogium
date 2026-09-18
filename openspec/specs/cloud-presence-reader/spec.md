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
  owns; and
- by placing, in time, minutes that playtime diffing has already counted.

A read is an acquisition of recorded fact. Deriving from it is a separate decision with its own
provenance and its own author, and must be introduced deliberately rather than arriving as a side
effect of fetching. Neither permitted path makes the cloud an author: the first supplies
observations to a mechanism that already exists, and the second supplies only *when* to a total
that Steam alone determines.

#### Scenario: A read of shared-game play

- **WHEN** a cloud read returns intervals naming games whose sessions come from observed presence
- **THEN** sessions may be written for them through that mechanism and its writer

#### Scenario: A read of owned-game play

- **WHEN** a cloud read returns intervals naming games whose sessions come from playtime diffing
- **THEN** they may inform where already-counted minutes are placed
- **AND** they SHALL NOT cause any minute to be added, removed, or created

#### Scenario: A read with no diffed playtime to place

- **WHEN** a cloud read returns intervals for an owned game and no playtime increase has been
  observed for it
- **THEN** no session is written for that game

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

The system SHALL provide a user-initiated action that re-files already-recorded sessions against
the presence record, SHALL treat it as a one-time event that repeated invocation does not
compound, and SHALL let the user reverse it, returning attribution to what it was.

The action SHALL disclose that it changes which dates play is credited to — and therefore quests
and streaks — and that it does not change experience, levels, or any game's total playtime.

#### Scenario: Re-filing history

- **WHEN** the user invokes the action
- **THEN** already-recorded sessions covered by the presence record are re-filed onto the dates
  they were played
- **AND** each game's total recorded minutes are unchanged

#### Scenario: Repeated invocation does nothing

- **WHEN** the action is invoked again after completing
- **THEN** no further change results

#### Scenario: Reversing it

- **WHEN** the user reverses a completed re-filing
- **THEN** attribution returns to what it was beforehand, and the action is offered again

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

A session derived from cloud presence SHALL be indistinguishable downstream from one derived by the
on-device observer: the same stored shape, the same writer, and the same participation in XP,
quests, streaks, history and analytics.

#### Scenario: Downstream treatment

- **WHEN** a session recovered from cloud presence is examined by any consumer
- **THEN** it participates identically to a session derived on-device

#### Scenario: Written through the shared writer

- **WHEN** recovered sessions are persisted
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
