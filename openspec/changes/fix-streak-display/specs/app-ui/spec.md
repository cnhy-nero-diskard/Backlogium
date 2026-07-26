## MODIFIED Requirements

### Requirement: Home screen
The system SHALL provide a Home screen showing the player's level and XP progress, today's daily
quest status, the current streak, and a "Now playing" indicator reflecting the player's current
in-game state. When credentials are configured, the Home screen SHALL also show a Steam account
card exposing the active SteamID and a masked API key with an action that reopens the onboarding
flow. When credentials are not configured, the Home screen SHALL present the onboarding flow as a
full-screen takeover rather than a static "Steam not configured" message. The streak count SHALL
reflect the streak through the last completed day, extended only once today's quest is actually
met, and SHALL NOT read as broken solely because today's quest has not yet been met while the day
is still in progress.

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
