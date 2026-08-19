## ADDED Requirements

### Requirement: Updates section
The Settings screen SHALL present, in release builds, an Updates section showing the running
version, when a check last completed, whether an update is available, and a control that checks
immediately. The section SHALL be absent in builds that a published release cannot upgrade.

#### Scenario: Viewing the section
- **WHEN** the Settings screen is shown in a release build
- **THEN** it presents the running version and when a check last completed

#### Scenario: No check has completed
- **WHEN** no check has ever completed
- **THEN** the section says so, rather than presenting an error or an empty value

#### Scenario: Update available
- **WHEN** an update is available
- **THEN** the section identifies the available version and offers to apply it

#### Scenario: Declined update still reachable
- **WHEN** the user has declined the available update
- **THEN** the section still shows it and still offers to apply it

#### Scenario: Checking manually
- **WHEN** the user activates the check control
- **THEN** a check runs immediately and the section reflects its outcome, including when the
  outcome is that no update exists

#### Scenario: Check fails
- **WHEN** a manual check cannot reach the release service
- **THEN** the section reports that the check did not complete and remains fully usable

#### Scenario: Development build
- **WHEN** the Settings screen is shown in a development build
- **THEN** the Updates section is absent
