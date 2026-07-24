## ADDED Requirements

### Requirement: Import Steam history control
The system SHALL provide a control that lets the user import their historical Steam playtime,
and SHALL reflect whether history has already been imported so the action is clearly one-time.

#### Scenario: Offering the import
- **WHEN** the user has not yet imported historical playtime
- **THEN** the UI presents a control to import Steam history

#### Scenario: Reflecting completed import
- **WHEN** historical playtime has already been imported
- **THEN** the control reflects the imported state rather than offering the import again as if unused

#### Scenario: Communicating the effect before importing
- **WHEN** the user is about to import history
- **THEN** the UI indicates that importing counts past playtime toward XP and is a one-time action

#### Scenario: Resetting a completed import
- **WHEN** historical playtime has been imported
- **THEN** the UI offers a control to reset the import, and after resetting the import is
  offered again
