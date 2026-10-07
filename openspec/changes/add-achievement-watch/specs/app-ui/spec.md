## ADDED Requirements

### Requirement: Unlock feedback uses one claimed presentation surface
The app SHALL present a claimed foreground unlock group accessibly with game identity, achievement names/count, and an action to the specific game detail. A background group SHALL use a notification carrying the same destination. Notification permission, alert preferences, current account, and hidden-game policy SHALL be checked before dispatch; suppressed groups SHALL NOT later flood the app.

#### Scenario: Several achievements
- **WHEN** a foreground group contains multiple unlocks
- **THEN** one presentation identifies the game and all unlocks/count without a burst of alerts

#### Scenario: Background game destination
- **WHEN** the player taps a background unlock notification
- **THEN** the app opens that game's detail after ordinary configured-account routing

#### Scenario: Unavailable notification permission
- **WHEN** a background group cannot be presented because permission or alerts are disabled
- **THEN** it is suppressed durably while achievement persistence remains successful

#### Scenario: Reduced motion and accessibility
- **WHEN** a group is presented with reduced motion or TalkBack
- **THEN** its content/actions remain understandable without animation or color alone
