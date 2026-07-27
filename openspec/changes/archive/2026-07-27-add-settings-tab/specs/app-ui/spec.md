## MODIFIED Requirements

### Requirement: App shell and navigation
The system SHALL present a Compose UI with navigation between Home, Library, History, and
Settings screens, and all screens SHALL render from locally stored state so the app
is fully usable offline.

#### Scenario: Offline launch
- **WHEN** the app is opened without network
- **THEN** all screens display the last synced state and never block on a network call

#### Scenario: Navigating between screens
- **WHEN** the user selects a destination from the app's navigation
- **THEN** the corresponding screen (Home, Library, History, or Settings) is shown

### Requirement: Home screen
The system SHALL provide a Home screen showing the player's level and XP progress, today's daily
quest status, the current streak, and a "Now playing" indicator reflecting the player's current
in-game state. Home SHALL present progress content only: account, sync, and data-management
controls belong to the Settings screen and SHALL NOT appear on Home. When a sync has failed,
Home SHALL surface the error together with an action that retries the sync, so a failure is
recoverable without leaving the screen. When credentials are not configured, the Home screen
SHALL present the onboarding flow as a full-screen takeover rather than a static "Steam not
configured" message. The streak count SHALL reflect the streak through the last completed day,
extended only once today's quest is actually met, and SHALL NOT read as broken solely because
today's quest has not yet been met while the day is still in progress.

#### Scenario: Viewing progress
- **WHEN** the Home screen is shown while configured
- **THEN** it displays current level with progress toward the next level, whether today's quest is
  met, and the current streak count

#### Scenario: Administration controls absent from Home
- **WHEN** the Home screen is shown while configured
- **THEN** it does not display the Steam account card, the manual sync trigger, the last-sync
  time, or the Steam history import

#### Scenario: Retrying a failed sync from Home
- **WHEN** the last sync failed and the Home screen displays the resulting error
- **THEN** the error presentation offers a retry action that enqueues a new sync

#### Scenario: Error cleared after a successful retry
- **WHEN** a retry triggered from Home completes successfully
- **THEN** the error presentation is no longer shown

#### Scenario: Now playing shown while in-game
- **WHEN** the live status reports the player is in a game
- **THEN** the Home screen shows a "Now playing" indicator with the running game's name
  (and its icon when resolvable)

#### Scenario: Now playing hidden when not in-game
- **WHEN** the live status reports the player is not in a game
- **THEN** the Home screen does not show a "Now playing" indicator

#### Scenario: Onboarding takeover when not configured
- **WHEN** no credentials are configured
- **THEN** the Home screen presents the onboarding flow (API key entry, then SteamID entry) as a
  full-screen takeover instead of a dead-end "Steam not configured" message

#### Scenario: Streak shown before today's quest is met
- **WHEN** today's quest has not yet been met and today is still in progress
- **THEN** the Streak card shows the streak count carried in from the last completed day, not zero,
  and does not present today as already having extended it

#### Scenario: Streak extends once today's quest is met
- **WHEN** today's quest becomes met
- **THEN** the streak count increases to include today, the same way it would for any other met day

#### Scenario: Streak breaks only once a day has concluded unmet
- **WHEN** a day ends without its quest ever being met (beyond any configured grace)
- **THEN** the streak count resets to zero starting from the next day, not while that day was still
  in progress

## ADDED Requirements

### Requirement: Sync-in-progress feedback in the app shell
The system SHALL indicate an in-flight sync in the app shell's profile header, so the cue is
visible from every top-level destination rather than only from the screen carrying the sync
trigger. The indicator SHALL reflect both scheduled and manually triggered syncs. Because a
sync can complete in well under a second, the indicator SHALL remain visible long enough to be
perceptible rather than flickering. The indicator SHALL respect the platform's reduced-motion
preference and SHALL NOT rely on motion as its only cue.

#### Scenario: Manual sync reflected in the header
- **WHEN** the user triggers a manual sync from Settings
- **THEN** the profile header shows the sync indicator until that sync completes

#### Scenario: Scheduled sync reflected in the header
- **WHEN** a periodic background sync runs while the app is in the foreground
- **THEN** the profile header shows the sync indicator for that sync as well

#### Scenario: Indicator visible across destinations
- **WHEN** a sync is in flight and the user navigates between top-level destinations
- **THEN** the indicator remains visible, because it belongs to the shell rather than to a screen

#### Scenario: Very fast sync remains perceptible
- **WHEN** a sync completes almost immediately after being enqueued
- **THEN** the indicator is still displayed for a perceptible minimum duration rather than
  appearing and vanishing within a frame or two

#### Scenario: Idle state
- **WHEN** no sync is in flight
- **THEN** the header shows no sync indicator and the header's identity content is unaffected

#### Scenario: Reduced motion honored
- **WHEN** the platform reports a reduced-motion preference
- **THEN** the indicator conveys the in-flight state without continuous animation

## REMOVED Requirements

### Requirement: Sync-in-progress feedback on Home
**Reason**: The manual sync trigger moves from Home to the Settings screen, so a requirement
scoped to "the trigger control on Home" no longer describes where either the control or its
feedback lives. Its two concerns are re-homed rather than dropped: the disabled-while-running
behavior of the trigger is now specified by the `app-settings` "Sync section" requirement, and
the in-flight visual cue is now specified by the "Sync-in-progress feedback in the app shell"
requirement added above, which additionally covers scheduled syncs.

**Migration**: No user-facing capability is lost. Manual sync feedback is now shown in the
profile header instead of inside the Home trigger button, and the trigger itself is reached
from Settings.
