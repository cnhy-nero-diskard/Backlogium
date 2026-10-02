## Purpose
Explain the origin and limits of displayed figures without changing their underlying computations or stored evidence.

## ADDED Requirements

### Requirement: A figure declares its provenance with a shared vocabulary
The app SHALL use Observed, Tracked, and Inferred consistently. Observed SHALL mean an unmodified source report; Tracked SHALL mean recorded activity assigned to app periods; Inferred SHALL mean an app calculation or estimate. Tracked SHALL NOT imply Steam-only or exact measured playtime.

#### Scenario: Remote lifetime counter
- **WHEN** a figure presents an unmodified Steam lifetime report
- **THEN** it is Observed and names Steam/freshness rather than implying a dated play distribution

#### Scenario: Mixed tracked period
- **WHEN** a period total includes counter-derived and shared-game presence activity
- **THEN** it is Tracked with a mechanism explaining the actual known mix

#### Scenario: Rule-derived counts
- **WHEN** the app counts quest-met days or achievements in computed rarity tiers
- **THEN** the counts are Inferred while the raw percentages remain source reports

### Requirement: Explanations are grounded in stored evidence
Provenance explanations SHALL use available evidence for the displayed data. Unknown/legacy mechanisms SHALL be described conservatively. Current game ownership or enabled cloud configuration SHALL NOT prove a historical session's source. Manual estimates SHALL be identified as user estimates.

#### Scenario: Borrowed becomes owned
- **WHEN** a game's current source is owned but old session source evidence is incomplete
- **THEN** the old amount is not relabelled as an exact Steam observation

#### Scenario: Proven cloud contribution
- **WHEN** a session has stored evidence of timing assistance or shared recovery
- **THEN** the explanation describes that contribution and retains its existing badge/reveal

#### Scenario: Unknown history
- **WHEN** a legacy session has no complete mechanism evidence
- **THEN** copy describes recorded activity and approximate boundaries without inventing cloud or Steam-only provenance

### Requirement: Provenance is disclosed at the figure and reachable accessibly
Each covered figure SHALL visibly declare provenance and offer a reachable mechanism explanation. A shared card label MAY cover figures only when their classification and mechanism agree. Essential source/period qualifiers SHALL remain legible in a screenshot and in accessibility semantics.

#### Scenario: Different mechanisms in one screen
- **WHEN** session counts and remote percentages appear together
- **THEN** each receives the appropriate qualifier rather than one blanket screen disclaimer

#### Scenario: Screen reader
- **WHEN** a reader focuses the number or its explanation action
- **THEN** the provenance, essential qualifier, and action purpose are announced

#### Scenario: Shared card
- **WHEN** several figures share the exact same mechanism
- **THEN** one associated card disclosure can explain them without repeated markers

### Requirement: Timing disclosure describes current attribution limits
Time-of-day/session-shaped figures SHALL identify estimated boundaries and local-start attribution, with a figure-specific caveat for delayed discovery. Explanations SHALL allow proven post-play/cloud assistance without asserting fixed timing tolerances or universal exactness.

#### Scenario: Play found after a long gap
- **WHEN** a Steam delta is attributed to an estimated earlier start
- **THEN** the time-of-day explanation states that timing may reflect the earlier check

#### Scenario: Improved boundary evidence
- **WHEN** cloud or post-play observations inform a session's boundary
- **THEN** the explanation acknowledges the evidence while retaining appropriate approximation

### Requirement: Confidence and provenance are complementary
Provenance SHALL preserve existing learning, reliable, and missing-estimate states. Personal Pace explanations SHALL name tracked activity/frequency, applicable HLTB remaining-work estimates, and deadline/capacity inputs where used. Confidence SHALL lead a combined legible message rather than being replaced by a generic derivation warning.

#### Scenario: Learning forecast
- **WHEN** recent tracking is insufficient for a reliable forecast
- **THEN** learning remains explicit and no definitive fit claim appears

#### Scenario: Reliable deadline forecast
- **WHEN** a complete forecast is shown
- **THEN** its derivation names remaining HLTB work, recent tracked pace, and time until deadline

#### Scenario: Missing estimate
- **WHEN** one or more applicable HLTB values are absent
- **THEN** missing inputs remain visible instead of being hidden by a provenance label

### Requirement: Disclosure preserves calculations and data lifecycle
The provenance change SHALL NOT alter persisted activity, quest outcomes, XP, period attribution, refresh traffic, cloud authorization, or source freshness. It SHALL describe current outputs using existing evidence.

#### Scenario: Marker selected
- **WHEN** a player opens a figure explanation
- **THEN** no refresh, recompute, or data write is triggered

#### Scenario: Cross-midnight session
- **WHEN** a session spans midnight
- **THEN** existing local-start attribution and displayed totals remain unchanged
