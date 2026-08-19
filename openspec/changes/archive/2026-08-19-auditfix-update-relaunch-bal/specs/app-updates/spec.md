## MODIFIED Requirements

### Requirement: Installation and relaunch
On the user's request and after verification, the system SHALL install the update and, on success,
relaunch the app. Any other outcome SHALL leave the installed app unchanged.

#### Scenario: Update applied
- **WHEN** a verified artifact is installed successfully
- **THEN** the app is relaunched on the new version

#### Scenario: Install succeeds while the app is backgrounded
- **WHEN** a verified artifact is installed successfully and the app has no visible activity at that
  moment
- **THEN** the app is not relaunched automatically, and a tap-to-open notification announcing the new
  version is posted instead, so the platform's background-activity-launch restriction is never
  attempted against

#### Scenario: Install declined at the system prompt
- **WHEN** the user cancels the system installation prompt
- **THEN** the app remains on its current version, is not relaunched, and the artifact is removed

#### Scenario: Install fails
- **WHEN** installation fails for any reason
- **THEN** the app remains on its current version and the failure is reported

#### Scenario: Permission to install not granted
- **WHEN** the user has not permitted this app to install applications
- **THEN** the requirement is explained and the user is directed to grant it, rather than the
  attempt failing without explanation
