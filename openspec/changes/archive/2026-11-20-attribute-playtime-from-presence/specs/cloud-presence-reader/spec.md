## MODIFIED Requirements

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
For an owned-game Steam increase, validated presence intervals SHALL inform only placement, never quantity. Already funded contributions SHALL be preserved. New interval placement SHALL be bounded by remaining Steam-authorized minutes and whole confirmed minutes of non-overlapping usable span, with deterministic proportional rounding. Unmatched minutes SHALL use playtime-recovery. Evidence-informed placements plus fallback allocations SHALL sum to the authoritative increase exactly.

Unknown or rejected coverage SHALL NOT supply timing. A raw interior-gap pair above the existing gap tolerance SHALL reject the entire interval, including a fresh tail: retained largest-gap evidence does not prove that other outages are absent. Fallback SHALL be explicitly unknown-time and SHALL NOT be described as validated timing or as avoiding proven outage portions.

#### Scenario: One increase across several observed intervals

- **WHEN** an increase has several validated observed intervals
- **THEN** its bounded evidence-informed share is distributed proportionally over their non-overlapping confirmed spans
- **AND** any residual is credited as unknown-time play and the combined quantity equals the increase

#### Scenario: The total is never changed

- **WHEN** confirmed spans total more than, less than, or equal to the observed increase
- **THEN** evidence-informed and fallback allocations together sum to that increase exactly

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
- **THEN** evidence-informed minutes are placed only against the confirmed portion
- **AND** any remaining authoritative minutes use separately labeled unknown-time fallback

#### Scenario: An interior gap exceeding tolerance removes the interval from placement

- **WHEN** an interval has an interior-gap pair above the placement tolerance even with a fresh tail
- **THEN** the entire interval contributes no usable timing span
- **AND** if no other timing remains, the increase uses unknown-time recovery rather than a timestamped fallback session

#### Scenario: Two interior outages make the interval unusable for placement

- **WHEN** an interval contains two outages above tolerance but retains only the largest pair
- **THEN** it contributes no usable timing span, including no tail placement
- **AND** Steam-authorized minutes remain eligible for unknown-time recovery

#### Scenario: Partial evidence leaves a residual
- **WHEN** an increase is 105 minutes and validated non-overlapping span supplies 35 whole minutes of placement
- **THEN** evidence-informed placement receives 35 and fallback receives 70

#### Scenario: Continuous evidence cannot bypass the cap
- **WHEN** a 36-minute Steam increase is observed across a 35-minute window fully covered by validated timing evidence
- **THEN** no more than 35 minutes are credited as timed, even when the evidence establishes continuity for an open or new session
- **AND** the remaining minute is credited through unknown-time recovery
- **AND** the existing rounding guard for independently usable ordinary same-date short-gap estimates is unchanged

#### Scenario: Duplicate evidence cannot multiply timing capacity
- **WHEN** local and cloud candidates overlap or the same cloud interval is replayed
- **THEN** overlapping usable span contributes timing capacity only once
- **AND** timed placement plus residual recovery still conserves the authoritative increase

### Requirement: Placement never degrades the estimate it replaces

Unavailable, unconfigured, unknown, rejected or insufficient cloud evidence SHALL leave all Steam-authorized owned-game minutes eligible for local playtime-recovery. No cloud configuration or read failure SHALL prevent fallback or require another network call before crediting a trustworthy Steam increase. Rejected intervals SHALL NOT be reused as valid timing by fallback. Ordinary short-gap monitoring with usable timing SHALL preserve its existing session behavior and shall not consult cloud merely for snapshot creation.

#### Scenario: Record unavailable

- **WHEN** the presence record cannot be read during a sync
- **THEN** the sync attributes minutes exactly as it does without the feature, and completes

#### Scenario: Feature not configured

- **WHEN** no cloud endpoint is configured
- **THEN** owned-game recovery operates through normal successful Steam reads without cloud requests

#### Scenario: A short period needs no correction

- **WHEN** the period since the previous observation is short enough that the existing estimate is
  already as precise as the record
- **THEN** the record is not consulted for that period

#### Scenario: A periodic read at the consultation threshold

- **WHEN** a periodic sync reaches the 30-minute consultation threshold and every positive owned-game increase independently qualifies for ordinary same-date short-gap estimation
- **THEN** it does not consult cloud for placement
- **AND** if any such increase does not qualify, the optional placement read remains eligible under the existing threshold

#### Scenario: Minutes are never lost

- **WHEN** placement is attempted and cannot be completed for any reason
- **THEN** the observed increase is still credited in full by the unaided attribution

#### Scenario: Every covering interval rejected retains the old estimate

- **WHEN** every covering interval is rejected and no other usable timing exists
- **THEN** the full authoritative increase receives unknown-time recovery attribution
- **AND** no timing claim is made from the rejected evidence

#### Scenario: Unknown coverage is not continuous timing
- **WHEN** all available cloud intervals have unknown coverage or only legacy transition estimates during ordinary placement
- **THEN** none supplies usable timing and unmatched minutes use local recovery
- **AND** the separately confirmed pre-data transfer exception is not applied to ordinary placement

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

#### Scenario: Partial evidence cannot re-date an unfunded legacy session

- **WHEN** confirmed non-overlapping spans inside the selected range and the session's recorded bounds cannot support all of an unfunded legacy session's minutes
- **THEN** the original session and provenance SHALL remain unchanged rather than creating a partially funded replacement or inferring an observation window
- **AND** when the full quantity is supported, each replacement SHALL fit within the whole confirmed minutes of its own span
- **AND** previously funded unknown-time allocations remain subject to the ordinary recovery rules

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

#### Scenario: Reversal preserves an independent recovery upgrade
- **WHEN** historical re-filing is reversed after a separate recovery correction upgraded a funded contribution
- **THEN** reversal preserves that independent change and the game's credited total rather than restoring a superseded estimate
- **AND** an operation without a provable mapping leaves that contribution intact
