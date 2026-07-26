## ADDED Requirements

### Requirement: Steam profile header
The system SHALL present a persistent profile header at the top of the app shell showing the
player's Steam avatar, persona name, and current presence state, and the header SHALL render
from locally stored identity so it is populated on an offline launch.

#### Scenario: Viewing the profile header
- **WHEN** any top-level screen is shown while credentials are configured
- **THEN** the header displays the player's Steam avatar and persona name

#### Scenario: Header persists across navigation
- **WHEN** the user navigates between top-level destinations
- **THEN** the header remains visible without re-loading

#### Scenario: Offline launch
- **WHEN** the app is launched without network after at least one successful sync
- **THEN** the header displays the last known persona name and avatar rather than a blank or
  loading state

#### Scenario: Identity not yet synced
- **WHEN** no persona name or avatar has been stored yet
- **THEN** the header displays a neutral fallback presentation instead of an empty or broken
  avatar

#### Scenario: Avatar image unavailable
- **WHEN** the avatar image fails to load
- **THEN** the header displays a themed fallback in place of the image and remains legible

#### Scenario: Presence reflected while in game
- **WHEN** the player is currently in a game and live status is being observed
- **THEN** the header reflects the in-game presence state

#### Scenario: Hidden while unconfigured
- **WHEN** Steam credentials are not configured
- **THEN** no profile header is shown, and the onboarding takeover is presented as it is today

#### Scenario: App XP level not duplicated
- **WHEN** the header is shown
- **THEN** it does not present a level number, so the player's Steam level is never confused
  with the app's own XP level
