## ADDED Requirements

### Requirement: Achievement watch setting
The Settings screen SHALL offer a switch controlling whether the app watches the running game's
achievements while the player is in a game, presented alongside the existing live-monitor switch. The
setting SHALL be enabled by default, SHALL state that it makes additional requests only while a game
is running, and SHALL persist across app restarts.

#### Scenario: Setting present
- **WHEN** the Settings screen is shown
- **THEN** a switch for the achievement watch is offered alongside the live-monitor switch

#### Scenario: Default state
- **WHEN** the player has never changed the setting
- **THEN** the watch is enabled

#### Scenario: Cost disclosed
- **WHEN** the setting is presented
- **THEN** it conveys that the watch makes additional requests only while a game is running

#### Scenario: Persisted
- **WHEN** the player changes the setting and restarts the app
- **THEN** the chosen state is still in effect

#### Scenario: Switching is felt
- **WHEN** the player switches the setting
- **THEN** the toggle intent is delivered once, as it is for every other binary setting

#### Scenario: Independent of the live monitor
- **WHEN** the live monitor is switched off
- **THEN** the achievement watch setting is unaffected, and still governs the watch while presence is
  observed by any other means
