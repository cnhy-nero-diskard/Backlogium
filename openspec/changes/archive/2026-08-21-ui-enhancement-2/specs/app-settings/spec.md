## MODIFIED Requirements

### Requirement: Sync section
The Settings screen SHALL present the time of the last successful sync and a control that
triggers an immediate manual sync.

The section SHALL present each operation it offers as its own row carrying that operation's name,
its own status, and its own action, so that every status shown in the section is adjacent to the
control it describes. A status the user cannot act on SHALL be presented as a row with no control
rather than sharing a control with an unrelated operation.

#### Scenario: Viewing last sync
- **WHEN** the Settings screen is shown
- **THEN** it displays when the last sync completed

#### Scenario: Triggering a manual sync
- **WHEN** the user activates the manual sync control
- **THEN** a one-time poll is enqueued and the app reflects the updated state when it completes

#### Scenario: Sync control while a sync runs
- **WHEN** a manual sync is already in flight
- **THEN** the control cannot be triggered again until that sync completes

#### Scenario: Each status sits with its own action
- **WHEN** the Sync section presents more than one operation
- **THEN** each operation's status is presented in the same row as the control that triggers it

#### Scenario: Status with no action
- **WHEN** the section reports the state of work the user cannot trigger
- **THEN** that state is presented as its own row without a control, rather than beside a control
  belonging to a different operation

#### Scenario: Rearrangement preserves control behaviour
- **WHEN** the section is presented in its rearranged form
- **THEN** every control's enabled and disabled conditions are unchanged from before the
  rearrangement
