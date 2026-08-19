## ADDED Requirements

### Requirement: Committed actions are felt as well as seen
Surfaces that commit a consequence SHALL accompany the outcome with the corresponding haptic
intent, alongside — never instead of — their existing visible result. This covers saving a rule
change, applying a restore, deleting a snapshot, switching the live monitor, and entering or
leaving Library selection mode.

#### Scenario: Rule change saved
- **WHEN** the player confirms a rule change and it is persisted
- **THEN** the success intent is delivered as the confirmation closes, and the existing visible
  result is unchanged

#### Scenario: Restore applied
- **WHEN** the player confirms a restore and it completes
- **THEN** the success intent is delivered once

#### Scenario: Snapshot deleted
- **WHEN** the player deletes a snapshot
- **THEN** the success intent is delivered once

#### Scenario: Live monitor switched
- **WHEN** the player turns the live monitor on or off
- **THEN** the toggle intent is delivered once

#### Scenario: Selection mode entered
- **WHEN** the player long-presses a Library row to enter selection mode
- **THEN** the toggle intent is delivered once, and the long-press gesture behaves as before

#### Scenario: Sync failure
- **WHEN** a sync the player initiated fails
- **THEN** the refusal intent is delivered once, alongside the existing error presentation

#### Scenario: Browsing remains silent
- **WHEN** the player navigates between destinations, opens a game, filters, sorts, or changes
  display density
- **THEN** no haptic feedback is delivered

#### Scenario: Feedback never replaces the visible result
- **WHEN** any committed action delivers haptic feedback
- **THEN** the action's outcome remains fully determinable without it
