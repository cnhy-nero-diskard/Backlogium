## ADDED Requirements

### Requirement: Completion times section
The Settings screen SHALL present a Completion times section showing the state of the applied
HowLongToBeat dataset — when its data was gathered and how many of the user's games it covers — a
control that checks for a newer dataset, and a control that produces a contribution file. The
section SHALL NOT offer any control that looks up HowLongToBeat across the library.

#### Scenario: Viewing the section
- **WHEN** the Settings screen is shown and a dataset has been applied
- **THEN** the section presents when the dataset's data was gathered and how many of the user's
  games it covers

#### Scenario: No dataset applied
- **WHEN** no dataset has ever been applied
- **THEN** the section says so and offers to obtain one, rather than presenting an error or an
  empty value

#### Scenario: Checking for a newer dataset
- **WHEN** the user activates the check control
- **THEN** a check runs immediately and the section reflects its outcome, including when the
  outcome is that the dataset is already up to date

#### Scenario: Control while a check runs
- **WHEN** a check or download is already in flight
- **THEN** the check control cannot be triggered again until it completes

#### Scenario: Check fails
- **WHEN** a check cannot reach the release service
- **THEN** the section reports that the check did not complete, remains fully usable, and continues
  to present the previously applied dataset as in effect

#### Scenario: Producing a contribution file
- **WHEN** the user activates the contribution control
- **THEN** what the file reveals is stated before any file is written, and the user chooses where it
  is written

#### Scenario: No library-wide lookup offered
- **WHEN** the Completion times section is shown
- **THEN** it offers no control that looks up HowLongToBeat across the library

#### Scenario: Each status sits with its own action
- **WHEN** the section presents more than one operation
- **THEN** each operation's status is presented in the same row as the control that triggers it
