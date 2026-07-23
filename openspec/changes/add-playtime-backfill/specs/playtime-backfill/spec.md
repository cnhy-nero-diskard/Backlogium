## ADDED Requirements

### Requirement: Opt-in historical playtime import
The system SHALL provide a user-initiated action that imports each game's pre-existing Steam
playtime so it counts toward XP, and SHALL NOT import historical playtime unless the user
invokes this action.

#### Scenario: Importing history
- **WHEN** the user invokes the import action
- **THEN** each game's historical Steam playtime is captured and included in that game's XP
  input, and the player's XP is recomputed to reflect it

#### Scenario: History not counted by default
- **WHEN** the user has never invoked the import action
- **THEN** XP reflects only playtime tracked after install, unchanged from current behavior

### Requirement: Bounded historical XP via the existing taper
The system SHALL compute XP from imported historical playtime using the engine's existing
per-game diminishing-returns rules, so a game with a known completion length cannot earn more
than its capped amount regardless of how many historical hours it holds.

#### Scenario: Matched game caps despite large history
- **WHEN** a game with a HowLongToBeat completion length is imported with historical playtime far exceeding twice that length
- **THEN** its contributed XP is capped by the taper, not proportional to the raw historical hours

#### Scenario: Combined historical and tracked playtime
- **WHEN** a game has both imported historical playtime and playtime tracked after install
- **THEN** its XP is computed from the combined total, tapered as a single cumulative amount

### Requirement: One-time idempotent import
The system SHALL treat the import as a one-time event: once history has been imported, the
system SHALL record that state and SHALL NOT re-import or double-count historical playtime on
subsequent syncs or repeated invocations, while continuing to track new playtime normally.

#### Scenario: Repeated invocation does nothing
- **WHEN** the user invokes the import action after history has already been imported
- **THEN** no additional historical playtime is added and XP is unchanged by the repeat

#### Scenario: New playtime still accrues after import
- **WHEN** the player plays more after importing history
- **THEN** the new tracked playtime is added on top of the imported history without re-importing it

#### Scenario: Growing Steam total is not re-imported
- **WHEN** a later sync observes a higher lifetime Steam total for an already-imported game
- **THEN** the increase is counted only as newly tracked playtime, not as additional imported history
