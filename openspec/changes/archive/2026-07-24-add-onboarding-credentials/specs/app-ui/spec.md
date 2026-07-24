## MODIFIED Requirements

### Requirement: Home screen
The system SHALL provide a Home screen showing the player's level and XP progress, today's daily
quest status, the current streak, and a "Now playing" indicator reflecting the player's current
in-game state. When credentials are configured, the Home screen SHALL also show a Steam account
card exposing the active SteamID and a masked API key with an action that reopens the onboarding
flow. When credentials are not configured, the Home screen SHALL present the onboarding flow as a
full-screen takeover rather than a static "Steam not configured" message.

#### Scenario: Viewing progress
- **WHEN** the Home screen is shown while configured
- **THEN** it displays current level with progress toward the next level, whether today's quest is
  met, and the current streak count

#### Scenario: Sync now from Home
- **WHEN** the user triggers "Sync now" from Home
- **THEN** a manual poll is enqueued and the screen reflects updated state and any sync error when
  it completes

#### Scenario: Now playing shown while in-game
- **WHEN** the live status reports the player is in a game
- **THEN** the Home screen shows a "Now playing" indicator with the running game's name
  (and its icon when resolvable)

#### Scenario: Now playing hidden when not in-game
- **WHEN** the live status reports the player is not in a game
- **THEN** the Home screen does not show a "Now playing" indicator

#### Scenario: Editing credentials from Home
- **WHEN** credentials are configured and the user activates the Steam account card's edit action
- **THEN** the onboarding flow opens so the user can change and re-save credentials

#### Scenario: Onboarding takeover when not configured
- **WHEN** no credentials are configured
- **THEN** the Home screen presents the onboarding flow (API key entry, then SteamID entry) as a
  full-screen takeover instead of a dead-end "Steam not configured" message
