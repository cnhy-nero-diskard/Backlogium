## MODIFIED Requirements

### Requirement: Collection summary derivation
The system SHALL derive each collection's banner and pacing values as a pure function of stored signals - cached HowLongToBeat completion lengths, stored achievement rows, playtime, a Personal Pace forecast, and an injected current date - with no network calls and no dependency on Android. A member's completion fraction SHALL be its playtime divided by its HowLongToBeat completionist length, clamped to 0.0-1.0, matching the definition the gamification engine's goal-progress uses. A collection's aggregate completion progress SHALL be the mean of its members' individual completion fractions, considering only members with a known completion length. Achievements remaining SHALL be the sum of locked achievements across members that have stored achievement data. Forecast uncertainty SHALL remain distinct from complete, on-track, and at-risk outcomes.

#### Scenario: Completion progress with HowLongToBeat data
- **WHEN** a completion-goal collection has members with cached completion lengths and playtime
- **THEN** the banner shows the aggregate completion fraction derived from those members

#### Scenario: Member without HowLongToBeat data
- **WHEN** a member has no cached completion length
- **THEN** it contributes no completion fraction, and the aggregate fraction considers only members that do

#### Scenario: Achievements remaining
- **WHEN** a completion-goal or deadline-goal collection has members with stored achievement data
- **THEN** the banner shows the total locked achievements remaining across those members

#### Scenario: Member without achievement data
- **WHEN** a member has no stored achievement data
- **THEN** it contributes zero to achievements remaining and does not fail the derivation

#### Scenario: Deadline countdown
- **WHEN** a deadline-goal collection has a target date
- **THEN** the banner shows the number of days from the injected current date to the target date

#### Scenario: Deadline passed
- **WHEN** a deadline-goal collection's target date is on or before the injected current date
- **THEN** the banner reflects that the deadline has passed rather than showing a negative countdown

#### Scenario: Forecast uncertainty preserved
- **WHEN** the Personal Pace profile is learning or required HLTB estimates are missing
- **THEN** the summary exposes that uncertainty and does not classify a future deadline as on track or at risk

#### Scenario: Empty collection
- **WHEN** a collection has no members
- **THEN** its banner presents an empty state with no derived progress, remaining, countdown, or pacing values

#### Scenario: Derivation issues no network calls
- **WHEN** a collection summary is derived
- **THEN** no Steam or HowLongToBeat network request is issued; only supplied local signals are used

### Requirement: Deadline estimate basis and hindsight
A deadline-goal collection SHALL let the user select one HLTB completion-length basis: Main Story (`comp_main`), Main + Extra (`comp_plus`), Completionist (`comp_100`), or All Styles (`comp_all`). The selected basis SHALL be persisted with the collection. Its deadline plan SHALL subtract stored playtime from each member's known selected estimate and compare the remaining minutes with Personal Pace's projected gaming capacity through the target date. Members without the selected estimate SHALL be identified as unknown and SHALL NOT be treated as zero minutes. Calendar minutes outside the Personal Pace forecast SHALL NOT count as playable capacity.

#### Scenario: Selecting the deadline basis
- **WHEN** the user configures a deadline-goal collection
- **THEN** the setup offers all four HLTB bases and persists the selected choice

#### Scenario: Reliable deadline fits
- **WHEN** the Personal Pace profile is reliable, every member has the selected estimate, and projected capacity covers unfinished work through a future target date
- **THEN** the collection is on track and presents no deadline-change recommendation

#### Scenario: Reliable deadline is infeasible
- **WHEN** the Personal Pace profile is reliable, every member has the selected estimate, and unfinished work exceeds projected capacity through a future target date
- **THEN** the collection is at risk and reports the required pace and capacity shortfall

#### Scenario: Forecast is still learning
- **WHEN** Personal Pace lacks sufficient history for a future deadline
- **THEN** the collection may report required known work but does not claim that the deadline fits or is infeasible

#### Scenario: Selected estimate is missing
- **WHEN** one or more members lack the selected HLTB estimate
- **THEN** the collection identifies the missing estimates and does not classify the future deadline as on track or at risk

#### Scenario: Deadline has arrived with unfinished work
- **WHEN** the target date is today or earlier and the non-empty collection still has known or unknown unfinished work
- **THEN** the collection reports that the deadline has arrived or passed and makes deadline intervention eligible regardless of forecast confidence

#### Scenario: Deadline has arrived after completion
- **WHEN** the target date is today or earlier and every member's selected estimated work is complete
- **THEN** the collection is complete and does not recommend changing the deadline

#### Scenario: Changing the deadline from the overview
- **WHEN** the user confirms a new date from an eligible overview shortcut
- **THEN** only the collection target date changes and the Personal Pace plan refreshes without opening the full customization form

## ADDED Requirements

### Requirement: Mode-aware Personal Pace guidance
The system SHALL apply Personal Pace only to collection modes that have completion or sequencing meaning. Deadline goals SHALL use their selected HLTB basis, completion goals SHALL use Completionist estimates, and ordered queues SHALL use Completionist estimates for the next unfinished member and, when complete data exists, the remaining queue. Basic lists SHALL NOT present a pacing forecast.

#### Scenario: Deadline goal pacing
- **WHEN** a deadline-goal collection has a target date and known selected estimates
- **THEN** it reports required pace through the target and reports feasibility only when history and estimate completeness permit

#### Scenario: Completion goal horizon
- **WHEN** a completion-goal collection has a reliable profile and Completionist estimates for all unfinished members
- **THEN** it reports an approximate completion horizon at the user's Personal Pace

#### Scenario: Ordered queue next-game horizon
- **WHEN** an ordered queue has a reliable profile and its next unfinished member has a Completionist estimate
- **THEN** it reports an approximate horizon for completing that next game

#### Scenario: Ordered queue total horizon requires complete data
- **WHEN** any unfinished queue member lacks a Completionist estimate
- **THEN** no definitive whole-queue completion horizon is reported

#### Scenario: Basic list has no pacing guidance
- **WHEN** a collection's mode is basic list
- **THEN** no Personal Pace forecast is attached to its summary

### Requirement: Conditional deadline intervention
The system SHALL make the direct `Change deadline` action eligible only for a non-empty deadline collection with unfinished or unknown work when the target date is today or earlier, or when a reliable and complete future forecast is at risk. It SHALL keep the action ineligible for on-track, learning, incomplete-data, empty, or completed future plans.

#### Scenario: Future at-risk plan is eligible
- **WHEN** a complete reliable forecast says unfinished work exceeds capacity through a future target
- **THEN** the direct deadline-change action is eligible

#### Scenario: Future on-track plan is ineligible
- **WHEN** a complete reliable forecast says capacity covers unfinished work through a future target
- **THEN** the direct deadline-change action is not eligible

#### Scenario: Uncertain future plan is ineligible
- **WHEN** the profile is learning or required HLTB data is missing for a future target
- **THEN** the direct deadline-change action is not eligible

#### Scenario: Arrived deadline is eligible
- **WHEN** a non-empty collection has unfinished or unknown work and its target is today or earlier
- **THEN** the direct deadline-change action is eligible

#### Scenario: Completed plan is ineligible
- **WHEN** no selected estimated work remains and no member estimate is unknown
- **THEN** the direct deadline-change action is not eligible

