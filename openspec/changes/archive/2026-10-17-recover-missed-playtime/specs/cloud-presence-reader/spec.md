## MODIFIED Requirements

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

#### Scenario: Minutes are never lost

- **WHEN** placement is attempted and cannot be completed for any reason
- **THEN** the observed increase is still credited in full by the unaided attribution

#### Scenario: Every covering interval rejected retains the old estimate
- **WHEN** every covering interval is rejected and no other usable timing exists
- **THEN** the full authoritative increase receives unknown-time recovery attribution
- **AND** no timing claim is made from the rejected evidence

## ADDED Requirements

### Requirement: Delayed owned-game evidence upgrades placement without a second credit
Validated evidence acquired after an owned-game recovery commit SHALL be eligible to replace unlocked estimates whose retained contribution window it covers, through the existing on-device correction path. The read itself SHALL not authorize minutes. The replacement SHALL conserve quantity, preserve protected/manual state, and be idempotent across repeated pages and reader restarts. Existing historical re-filing and reversal SHALL not restore superseded estimates or duplicate upgraded contributions; operations lacking a provable mapping SHALL leave those contributions intact.

#### Scenario: Late evidence after baseline retirement
- **WHEN** valid late timing evidence arrives for a retained recovery contribution after its Steam baseline has advanced
- **THEN** placement can be corrected without re-diffing that baseline or adding another quantity credit

#### Scenario: Historical reversal after an upgrade
- **WHEN** a historical operation is reversed after an independently applied recovery upgrade
- **THEN** the reversal preserves the independent contribution change and the game's credited total
