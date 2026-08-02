## ADDED Requirements

### Requirement: Opt-in live monitor setting
The Settings screen SHALL provide an off-by-default Live monitor control. When enabled, it SHALL
keep the app's user-started foreground presence monitor active while no game is running, so a
subsequently started game can be detected without reopening the app or waiting for periodic sync.

#### Scenario: Enabling live monitor
- **WHEN** the user enables Live monitor from Settings
- **THEN** the preference is persisted and the foreground monitor begins while the app is visible

#### Scenario: Disclosing ongoing monitoring
- **WHEN** the Live monitor control is presented
- **THEN** it discloses its 30-second network checks, ongoing notification, battery/data use, and
  Android's approximate six-hour background-service limit

#### Scenario: Disabling live monitor
- **WHEN** the user disables Live monitor while no game is running
- **THEN** idle monitoring stops and its ongoing notification is removed
