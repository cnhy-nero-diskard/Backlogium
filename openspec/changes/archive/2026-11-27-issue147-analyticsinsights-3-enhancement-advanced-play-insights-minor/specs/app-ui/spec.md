# App UI Delta

## ADDED Requirements

### Requirement: Advanced insights preserve window and evidence scope
Analytics SHALL present genre mix and drift, weekday by time-of-day activity, windowed achievement momentum and expandable records using readable scope, units and coverage labels.

#### Scenario: Accessible alternative
- **WHEN** the player inspects a genre chart or weekday-by-time matrix using a screen reader or enlarged text
- **THEN** the same categories and amounts are available through an accessible textual equivalent

#### Scenario: Achievement comparison and lifetime disagree
- **WHEN** the selected period contains fewer confirmed dated unlocks than the lifetime rarity breakdown
- **THEN** each section states its own scope and can be read independently
