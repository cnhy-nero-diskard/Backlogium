## ADDED Requirements

### Requirement: Diagnostics section
The Settings screen SHALL provide access to a diagnostics view listing recent sync runs, with each
run inspectable to see what it did and how it ended. The view SHALL render from stored records
without a network call, and SHALL NOT display credential values in any form.

#### Scenario: Opening diagnostics
- **WHEN** the user activates the diagnostics control in Settings
- **THEN** a view listing recent sync runs is shown, most recent first

#### Scenario: Inspecting a run
- **WHEN** the user selects a recorded run
- **THEN** its trigger, duration, request count, work performed, and outcome are shown

#### Scenario: Diagnostics render offline
- **WHEN** the diagnostics view is opened without network
- **THEN** it displays stored records and never blocks on a network call

#### Scenario: No records yet
- **WHEN** the diagnostics view is opened before any run has been recorded
- **THEN** it presents an empty state rather than an error or a blank screen

#### Scenario: Credentials absent from diagnostics
- **WHEN** any diagnostics view or record detail is displayed
- **THEN** no Steam API key or credential value appears, in masked form or otherwise
