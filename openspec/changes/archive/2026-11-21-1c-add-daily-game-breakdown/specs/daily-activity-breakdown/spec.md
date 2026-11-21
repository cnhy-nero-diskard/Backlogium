## Purpose

Explain a local day's per-game recorded activity and its relationship to authoritative quest credit without manufacturing missing play evidence.

## ADDED Requirements

### Requirement: Daily game rows follow the canonical session date
The system SHALL provide per-game recorded minutes for a requested local date using canonical dated activity. Unlinked session minutes follow local start dates. Funded timed shares and dated allocations retain their frozen credited dates. It SHALL include recorded minutes of open sessions without adding elapsed wall-clock time, exclude hidden games, and retain visible sessions whose game identity is unavailable under a fallback identity. Undated quantity SHALL stay outside dated game rows.

#### Scenario: Multiple sessions for one game
- **WHEN** two visible sessions start on the requested day for the same game
- **THEN** one game row shows their summed recorded minutes

#### Scenario: Session crosses midnight
- **WHEN** a session starts yesterday and remains open today
- **THEN** its recorded minutes remain on yesterday's breakdown and are not also counted today

#### Scenario: Game is hidden
- **WHEN** a hidden game has sessions on the requested date
- **THEN** its identity, rows, and minutes are absent from the visible recorded subtotal

#### Scenario: Identity unavailable
- **WHEN** a visible session cannot be joined to a current game identity
- **THEN** its minutes remain in a fallback game row instead of disappearing

#### Scenario: Timed and recovered activity share a date
- **WHEN** a date includes unlinked session minutes, funded timed shares or dated recovery allocations
- **THEN** each visible credited amount appears once under its canonical date and dated estimates retain an accessible explanation

#### Scenario: Recovery after a timezone change
- **WHEN** the device timezone changes after a recovery placement was credited
- **THEN** frozen placements remain on their credited dates while unlinked session minutes follow the canonical local session date

#### Scenario: Imported or estimated playtime
- **WHEN** undated lifetime imports, manual estimates or pending recovery minutes exist
- **THEN** they do not receive a date or game row in the daily breakdown

### Requirement: Daily breakdown reconciles evidence and stored quest credit explicitly
The system SHALL distinguish the visible recorded subtotal, stored daily play total, stored Focus play total, and stored quest outcome. Reconciliation SHALL compare visible play with stored daily play. Neither scope SHALL overwrite the other. Recorded zero, absent activity, and unavailable progress SHALL remain distinguishable.

#### Scenario: Totals agree
- **WHEN** visible recorded minutes and stored credited minutes both equal 45
- **THEN** the breakdown reconciles to the 45-minute quest total without an adjustment

#### Scenario: Credit exceeds visible sessions
- **WHEN** stored credit is 60 minutes and visible sessions sum to 40
- **THEN** the breakdown shows the 40-minute visible subtotal and explains the 20-minute credit difference without revealing hidden identities or inventing another game's minutes

#### Scenario: Visible sessions exceed credit
- **WHEN** visible sessions sum to 50 minutes and stored credit is 30
- **THEN** both totals remain visible with their 20-minute difference and the stored quest outcome remains authoritative

#### Scenario: Focus-only quest
- **WHEN** a date has 60 stored daily minutes and 25 stored Focus minutes
- **THEN** all-play and Focus totals are identified separately, and a Focus-only quest uses the stored Focus amount and outcome
- **AND** dated views do not infer historical Focus membership from current game flags

#### Scenario: Progress-only record
- **WHEN** a date has stored daily play but no visible dated game activity
- **THEN** the credit remains visible and the per-game allocation is unavailable

#### Scenario: Sessions without recorded credit
- **WHEN** visible sessions exist but no daily credit row exists
- **THEN** their recorded minutes remain inspectable with unavailable credit and no invented quest outcome

### Requirement: Daily breakdown is a coherent bounded read
The system SHALL read visible canonical activity, identity visibility, and stored progress from one coherent, date-bounded local snapshot. Inspection SHALL cause no activity writes, XP awards, external requests or sync jobs. During recomputation it SHALL retain a prior coherent snapshot with an updating state or show initial loading.

#### Scenario: Correction is being committed
- **WHEN** session placement and daily credit change together
- **THEN** the breakdown updates from a coherent committed snapshot instead of presenting an intermediate reconciliation difference as a recorded fact

#### Scenario: Local date changes
- **WHEN** midnight changes the requested date
- **THEN** the previous day's rows are not relabelled as today's and the new date's breakdown is loaded

#### Scenario: Offline inspection
- **WHEN** the player expands a cached daily breakdown while offline
- **THEN** its available local evidence appears without any network request or progress mutation
