## ADDED Requirements

### Requirement: Notification permission requested in-app
On platform versions that require a runtime grant for posting notifications, the app SHALL request
that permission from within the app, so notification-bearing features work on a fresh install
without the user locating a system settings toggle unaided.

#### Scenario: Permission not yet granted
- **WHEN** the app runs on a platform version requiring a runtime notification grant and the
  permission has not been granted or denied
- **THEN** the app requests it

#### Scenario: Permission granted
- **WHEN** the notification permission is granted
- **THEN** the ongoing now-playing notification is posted and updated as specified

#### Scenario: Permission denied
- **WHEN** the user denies the notification permission
- **THEN** the app continues to function, presence tracking is unaffected, and no notification is
  posted

#### Scenario: Permission not re-requested
- **WHEN** the user has already responded to the request
- **THEN** the app does not repeatedly prompt on subsequent launches
