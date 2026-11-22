# Game Sources Delta

## ADDED Requirements

### Requirement: Shared-game admission notices identify their destination
An admission notification SHALL carry the initiating account and exact admitted game as its destination. Distinct notices SHALL retain their own destinations when multiple games are admitted.

#### Scenario: Two notices remain visible
- **WHEN** the player taps the earlier of two distinct admission notices
- **THEN** navigation targets the game named by the tapped notice
