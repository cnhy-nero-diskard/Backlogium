## Purpose

Recover Steam-authoritative post-baseline playtime after missing observations, while preserving exact totals and distinguishing dated estimates from timestamped sessions.

## ADDED Requirements

### Requirement: Successful observations retain a durable per-game baseline
The system SHALL retain account ID, game ID, absolute cumulative Steam minutes, observation instant, device local date and timezone for each trustworthy owned-game read. It SHALL retain the latest usable per-game quantity baseline and a daily snapshot of successful readings. Same-day successful reads SHALL update that day's snapshot without creating another quantity detector. First observation SHALL establish a baseline and credit no pre-baseline lifetime minutes. Missing games, absent cumulative fields, malformed or negative values, failed reads and inaccessible/private responses SHALL NOT become zero or advance the usable baseline. A cumulative decrease SHALL preserve the quantity high-water mark and its recovery boundary until a trustworthy reading reaches or exceeds it.

#### Scenario: First reading
- **WHEN** a previously unbaselined owned game reports 500 cumulative minutes
- **THEN** 500 is retained as its baseline and no recent play is credited

#### Scenario: Downtime survives process death
- **WHEN** the phone restarts after several days without a successful reading
- **THEN** the next trustworthy reading is compared with the persisted baseline for that account and game

#### Scenario: Missing field is not zero
- **WHEN** a successful response envelope omits a game's cumulative field or the game itself
- **THEN** that game's last-good quantity and observation boundary remain unchanged

#### Scenario: Regression cannot manufacture replayed minutes
- **WHEN** readings move from 500 to 450 and later return to 500
- **THEN** no minutes are credited, the regression does not shorten the recovery window, and a later increase is measured beyond 500

### Requirement: Unknown-time play is durable activity rather than a fabricated session
The system SHALL represent recovered day-level play with account/game identity, credited minutes, attributed date, frozen allocation zone, source observation window, allocation method and stable contribution identity. Exact session start and end SHALL be absent for unknown-time play. Same-day and consecutive-day fallback SHALL be labeled derived unknown-time activity; an observation window spanning multiple recovery dates before evidence exclusions SHALL remain labeled multi-day estimated activity even if rounding leaves only one positive allocation. Quantity and placement certainty SHALL be separate facts. An observation instant SHALL NOT be presented or exported as a gameplay boundary.

#### Scenario: Honest day-level activity
- **WHEN** 70 minutes are recovered without usable session boundaries
- **THEN** the activity has credited minutes and a date but no synthetic midnight start or end time

#### Scenario: Uncertainty persists
- **WHEN** multi-day estimated activity survives restart, export and import
- **THEN** its allocation method and explanation window remain available

### Requirement: Single-day fallback credits the newly observed date
When unmatched positive minutes have no usable timing evidence and the previous usable observation and recovery reading fall on the same or consecutive dates in the window's allocation zone, the system SHALL credit the unmatched minutes to the recovery date as unknown-time activity. It SHALL NOT extend a stale open synthetic session merely because one remains stored. Ordinary same-date short-gap monitoring SHALL retain its existing session behavior when the observation gap is at most 30 minutes, its delta is no greater than the ceiling of elapsed minutes plus one rounding minute, and no clock anomaly or known publication-delay conflict invalidates that estimate. Across midnight, session continuity SHALL require validated timing evidence; a stored open row alone is not continuity evidence.

#### Scenario: Consecutive-day recovery
- **WHEN** Tuesday's usable reading is 500, Wednesday's is 570, and no usable session timing exists
- **THEN** Wednesday receives 70 unknown-time minutes and Tuesday receives none of that increase

#### Scenario: Same-day recovery
- **WHEN** two trustworthy readings on one date increase by 20 without usable timing evidence
- **THEN** that date receives 20 unknown-time minutes

### Requirement: Multi-day residual is distributed exactly across eligible dates
For a multi-day gap the system SHALL distribute only unmatched positive minutes over the dates after the previous usable snapshot through the recovery date, inclusive, after expressing both observation instants in the recovery reading's device timezone. That zone and the resulting date keys SHALL be frozen for the allocation. Dates wholly accounted for by usable timing evidence SHALL be excluded from residual distribution; a date with merely partial timing coverage SHALL remain eligible for its unknown portion. For residual R and N eligible dates, each date SHALL receive floor(R/N) minutes and the earliest R modulo N dates SHALL receive one additional minute. Zero-minute shares SHALL NOT create activity or count as active days. No date before the normalized baseline date or after the recovery date SHALL be invented. If timing anomalies prevent a valid date window, the system SHALL retain the authoritative minutes as pending date-unknown credit for later placement, rather than lose them or fabricate a date.

#### Scenario: Four-day exact distribution
- **WHEN** Monday's reading is 500 and Friday's is 605 with no timing evidence
- **THEN** Tuesday, Wednesday, Thursday and Friday receive 27, 26, 26 and 26 minutes respectively
- **AND** all four positive allocations are marked multi-day estimated

#### Scenario: Remainder smaller than the date window
- **WHEN** two minutes are recovered over four eligible dates
- **THEN** the earliest two dates receive one minute each and later dates receive no activity
- **AND** both positive allocations remain multi-day estimated

#### Scenario: Partial date coverage
- **WHEN** usable evidence places 35 of a 105-minute increase on Wednesday but does not account for all of Wednesday
- **THEN** the 35-minute placement remains and only 70 minutes are distributed over eligible unknown portions of Tuesday through Friday

#### Scenario: Timezone change
- **WHEN** the device timezone changes between baseline and recovery
- **THEN** both instants are normalized in the recovery zone, their original observed date/zone metadata is retained, and later timezone changes do not move the resulting stored allocations

#### Scenario: No valid calendar window
- **WHEN** a positive authoritative increase arrives with an inverted clock window
- **THEN** its quantity is retained exactly once as pending date-unknown activity, with a timing anomaly explanation, until valid placement becomes possible

### Requirement: Timing evidence places a bounded share without authoring quantity
The system SHALL prefer validated local or cloud timing evidence over fallback placement, while Steam remains the sole quantity authority for owned games. Contributions already funded by the same Steam increase SHALL retain their quantity and placement and SHALL be counted once. Newly usable intervals without an existing funded contribution SHALL place at most the remaining Steam-authorized minutes and at most the whole confirmed minutes of their non-overlapping usable span. Placement among those intervals SHALL use proportional weights with deterministic largest-remainder rounding. The rest SHALL use unknown-time fallback. Duplicate, overlapping, rejected, wrong-account or stale-generation evidence SHALL NOT multiply the usable span or authorize additional minutes. Mere presence of an interval on a date SHALL NOT prove full coverage of that date.

#### Scenario: Bounded partial evidence
- **WHEN** a 105-minute Steam increase has 35 usable confirmed minutes in one interval and no other timing evidence
- **THEN** 35 minutes are placed against that interval and 70 remain for fallback
- **AND** the combined credited quantity is exactly 105

#### Scenario: Earlier credit already advanced the baseline
- **WHEN** 35 minutes were already committed from 500 to 535 and the next reading is 605
- **THEN** only 70 new minutes are credited and the previously committed 35 are not subtracted again

#### Scenario: Duplicate intervals
- **WHEN** local and cloud observations describe overlapping play for the same game
- **THEN** the overlap contributes usable span once and does not create a second quantity credit

### Requirement: Reconciliation replaces placement idempotently
The system SHALL permit delayed validated timing evidence to upgrade unlocked fallback allocations within their retained observation window. It SHALL replace or reduce the affected estimates and add their replacement placement in one atomic change, conserving the contribution's credited quantity. Repeating reconciliation with the same evidence SHALL change nothing further. It SHALL preserve separately imported lifetime offsets, manual edits, protected placements and unrelated activity. It SHALL NOT infer an old synthetic session's observation window solely from its start/end timestamps. Existing synthetic minutes SHALL be split or replaced only where their funded contribution and observation window are provable; otherwise their recorded history SHALL remain intact. Evidence upgrades SHALL retain an audit link to the replaced estimate.

#### Scenario: Late timing evidence
- **WHEN** a retained 105-minute estimate later obtains validated timing evidence for 35 minutes
- **THEN** that share is moved to evidence-informed placement and the residual is recalculated without increasing the game's credited total

#### Scenario: Reconciliation retry
- **WHEN** the same evidence is reconciled after a crash or repeated catch-up
- **THEN** no duplicate allocation, session, XP input or progress event is produced

#### Scenario: Unprovable legacy session
- **WHEN** a legacy synthetic session has no reliable contribution/window record
- **THEN** automatic recovery leaves it unchanged rather than treating its approximate boundaries as historical snapshots

### Requirement: All credited activity participates once in progression and reporting
Timestamped sessions, dated unknown-time allocations and pending date-unknown quantity SHALL feed a shared per-game credited-minute total exactly once under existing hidden-game, manual-estimate and lifetime-backfill policies. Dated allocations SHALL be eligible for daily totals, Focus totals, quests, streaks and dated charts. Pending date-unknown quantity SHALL remain visible and eligible for cumulative XP but SHALL not satisfy a dated quest until placed. Placement-only corrections SHALL leave per-game XP and level unchanged under unchanged rules and inputs, recompute affected old and new dates through corrective provenance, preserve longest-streak protections and retain previously owed events. Unknown-time activity SHALL NOT count as a session or supply session-length or time-of-day evidence.

#### Scenario: Quest eligibility
- **WHEN** an estimated allocation reaches a date's configured qualifying-minute threshold
- **THEN** that date's quest can be met and contribute to its streak while remaining visibly estimated

#### Scenario: Placement correction
- **WHEN** the same credited minutes move between dates under unchanged rules
- **THEN** daily totals and quests are corrected, total XP is unchanged, and no new earned-progress event is emitted

#### Scenario: Unknown clock-time metrics
- **WHEN** an Analytics period contains only day-level allocations
- **THEN** played minutes and game totals are available while session-shape and time-of-day metrics have no invented observations

### Requirement: Recovery is account-safe and atomically replayable
Baseline advancement, contribution authorization and resulting raw activity/daily credit SHALL commit together. Concurrent periodic and targeted observations SHALL derive quantity from the baseline inside the committing transaction. Stable contribution identities SHALL survive export/import and placement replacement. Failed transactions SHALL leave the increase retryable. Account changes and shared-to-owned conversions SHALL establish the appropriate fresh baseline without importing lifetime play as new activity; observations for an inactive account SHALL be rejected. A targeted observation's known play instant SHALL remain evidence rather than be overwritten by its later fetch time.

#### Scenario: Concurrent observers
- **WHEN** periodic and targeted sync both read the same increase before either commits
- **THEN** the increase is committed once and the later commit creates no additional minutes

#### Scenario: Transaction interruption
- **WHEN** persistence fails before completion
- **THEN** neither the baseline nor a partial quantity credit is left advanced

#### Scenario: Account switch
- **WHEN** the next observation belongs to a newly configured account
- **THEN** no comparison against the previous account's baseline creates activity

### Requirement: Recovery works without cloud and discloses its limits
The system SHALL perform fallback recovery through normal successful cumulative-playtime reads without requiring cloud setup, an always-on phone poller or additional network requests to manufacture snapshots. It SHALL expose pending/unavailable reading status without claiming that Offline/Invisible play is visible in real time. Family-shared games without owned cumulative totals SHALL retain their evidence-based mechanism and SHALL NOT use snapshot recovery. Very long gaps SHALL preserve exact post-baseline quantity with deterministic calendar arithmetic, bounded storage/query work and no arbitrary truncation of credited minutes.

#### Scenario: Cloud disabled
- **WHEN** a multi-day increase is read while cloud integration is disabled
- **THEN** fallback recovery succeeds locally without attempting a cloud request

#### Scenario: Steam has not published playtime
- **WHEN** Steam remains inaccessible or has not yet published an increase
- **THEN** no invisible play is invented and the interface explains that recovery awaits an available updated total

#### Scenario: Family-shared play
- **WHEN** a family-shared game has no owned cumulative total
- **THEN** it remains on the existing presence/manual path and receives no snapshot fallback

#### Scenario: Very long gap
- **WHEN** a valid baseline and recovery reading span ten years with a positive increase
- **THEN** the exact increase is retained and deterministically attributed without importing pre-baseline lifetime play or allocating zero-minute activity rows
