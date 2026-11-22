# App UI Delta

## ADDED Requirements

### Requirement: Offline state is visible across normal destinations
The app SHALL expose one persistent, accessible offline status when the active network lacks validated internet access. The status SHALL leave local navigation and actions usable and SHALL clear when access returns.

#### Scenario: Offline with cached data
- **WHEN** the user navigates from Library to a pushed detail screen while offline
- **THEN** the offline status remains available and local content stays interactive

#### Scenario: Steam fails while internet is validated
- **WHEN** a Steam request fails on a validated connection
- **THEN** the operation reports its own error and the app-wide status does not report offline

### Requirement: Essential actions have clear feedback
The app SHALL present action feedback near the affected surface or through the existing snackbar host. It SHALL use persistent inline feedback for errors requiring attention and concise transient feedback for completed actions with retry or undo when supported.

#### Scenario: One action reaches multiple observers
- **WHEN** an operation changes both its screen and a global status indicator
- **THEN** the operation has one action-result message, with readable localized copy and no raw endpoint URL

#### Scenario: User returns to an interrupted action
- **WHEN** an actionable persistent error still applies
- **THEN** the affected surface exposes the result and recovery action without relying on a prior transient message
