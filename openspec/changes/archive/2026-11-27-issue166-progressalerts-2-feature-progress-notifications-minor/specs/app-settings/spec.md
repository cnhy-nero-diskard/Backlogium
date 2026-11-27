# App Settings Delta

## ADDED Requirements

### Requirement: Progress alert preferences are independent
Settings SHALL provide independent preferences for new games, broken streaks, daily quota, completed games, and yesterday's recap. New categories SHALL default off. Existing shared-admission delivery SHALL retain its default and have a separate control alongside new owned-game alerts.

#### Scenario: Toggle a category
- **WHEN** the player disables the daily quota category
- **THEN** other progress categories and the existing achievement-watch settings keep their independent values

#### Scenario: Permission or vibration unavailable
- **WHEN** Android denies notifications or the selected channel has vibration disabled
- **THEN** Settings describes the current state and offers the applicable system settings action

#### Scenario: Upgrading with existing shared-admission alerts
- **WHEN** the player upgrades from a version with the Family Shared notification channel
- **THEN** the channel keeps its existing system settings and owned-game alerts start off
