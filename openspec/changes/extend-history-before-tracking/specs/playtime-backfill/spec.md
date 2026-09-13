## ADDED Requirements

### Requirement: Imported history carries no date
Imported historical playtime SHALL be treated as undated. The system SHALL NOT attribute it to any
calendar day, and SHALL NOT infer a date for it from a game's achievements, its last-played time, or
any other signal.

#### Scenario: Import attributed to no day
- **WHEN** the player imports Steam history
- **THEN** no calendar day's recorded play or attributed XP changes as a result

#### Scenario: No date inferred
- **WHEN** a game with imported history also has dated achievement unlocks
- **THEN** the imported playtime is not attributed to any of those dates

#### Scenario: Undated in every per-day accounting
- **WHEN** any surface accounts for play or XP by day
- **THEN** imported historical playtime is accounted for outside that per-day breakdown, not
  distributed into it

### Requirement: Imported history is visible in a per-day accounting
Where a surface accounts for XP by day, the system SHALL make the XP contributed by imported
historical playtime visible as an undated amount, so a per-day accounting reconciles with the
player's total rather than appearing to lose the import.

#### Scenario: Import visible in the accounting
- **WHEN** history has been imported and XP is accounted for by day
- **THEN** the import's contribution is presented as an undated amount identified as coming from the
  import

#### Scenario: No import
- **WHEN** history has never been imported
- **THEN** no undated amount is attributed to an import

#### Scenario: After a reset
- **WHEN** the player resets a completed import
- **THEN** the undated amount attributed to the import is no longer presented
