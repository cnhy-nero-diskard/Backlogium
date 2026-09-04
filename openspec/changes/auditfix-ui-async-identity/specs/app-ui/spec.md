# app-ui

## ADDED Requirements

### Requirement: The History current day expands when it becomes current
The History screen SHALL expand the current day by default whenever the current day changes,
not only on first composition. When the local date advances while the screen is composed or
retained — across midnight, or on resume after midnight — the newly current day SHALL be
expanded.

The screen SHALL track **which** date it auto-expanded rather than whether it has
auto-expanded at all. A one-time flag cannot satisfy this requirement, because a second
current day arrives after the flag is already set.

The player's own choices SHALL still hold: manually collapsing the current day SHALL survive
unrelated state emissions, and SHALL NOT be undone by data arriving for that same day. Only a
change of which date is current SHALL trigger a further auto-expansion.

#### Scenario: Date advances while the screen is composed
- **WHEN** the local date advances past midnight while the History screen is composed
- **THEN** the newly current day is expanded

#### Scenario: Resumed after midnight
- **WHEN** the History screen is backgrounded before midnight and resumed after it, with the
  destination retained
- **THEN** the newly current day is expanded rather than rendering collapsed

#### Scenario: Manual collapse of the current day sticks
- **WHEN** the player collapses the current day and further history data arrives for it
- **THEN** it stays collapsed, because the auto-expansion applies to a date becoming current
  and not to every emission

#### Scenario: Earlier days remain collapsed
- **WHEN** a new current day is auto-expanded
- **THEN** the day that was previously current is not force-collapsed or force-expanded by
  this rule, and earlier days remain as the player left them

#### Scenario: First open is unchanged
- **WHEN** the History screen is opened
- **THEN** the current day is expanded and all earlier days are collapsed, exactly as before
