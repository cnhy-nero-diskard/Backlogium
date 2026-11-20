## Purpose

Explain a local day's per-game recorded activity and its relationship to authoritative quest credit without manufacturing missing play evidence.

## ADDED Requirements

### Requirement: Daily game rows follow the canonical session date
The system SHALL provide per-game recorded minutes for a requested local date using the same session-start date attribution as History, Analytics, and daily progress. It SHALL include recorded minutes of open sessions without adding elapsed wall-clock time, exclude hidden games, and retain visible sessions whose game identity is unavailable under a fallback identity. Undated lifetime imports and manual estimates SHALL NOT become dated game rows.

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

#### Scenario: Imported or estimated playtime
- **WHEN** undated imported or manually estimated minutes exist without dated session evidence
- **THEN** they do not receive a date or game row in the daily breakdown

### Requirement: Daily breakdown reconciles evidence and stored quest credit explicitly
The system SHALL distinguish the visible recorded subtotal from the authoritative stored daily credited total and quest outcome. When both totals are available and differ, it SHALL expose the signed reconciliation difference as a comparison, not as an invented game's played minutes. It SHALL neither rescale visible sessions to fit credit nor infer that all differences come from hidden games, imports, or lost data. Missing credit SHALL remain unavailable rather than being derived from visible sessions. Recorded zero, absent activity, and unavailable credit SHALL remain distinguishable.

#### Scenario: Totals agree
- **WHEN** visible recorded minutes and stored credited minutes both equal 45
- **THEN** the breakdown reconciles to the 45-minute quest total without an adjustment

#### Scenario: Credit exceeds visible sessions
- **WHEN** stored credit is 60 minutes and visible sessions sum to 40
- **THEN** the breakdown shows the 40-minute visible subtotal and explains the 20-minute credit difference without revealing hidden identities or inventing another game's minutes

#### Scenario: Visible sessions exceed credit
- **WHEN** visible sessions sum to 50 minutes and stored credit is 30
- **THEN** both totals remain visible with their 20-minute difference and the stored quest outcome remains authoritative

#### Scenario: Progress-only record
- **WHEN** a date has stored daily credit but no individual session evidence
- **THEN** the credit remains visible and the per-game allocation is unavailable

#### Scenario: Sessions without recorded credit
- **WHEN** visible sessions exist but no daily credit row exists
- **THEN** their recorded minutes remain inspectable with unavailable credit and no invented quest outcome

### Requirement: Daily breakdown is a coherent bounded read
The system SHALL produce each breakdown from a coherent snapshot of the requested date, visible session evidence, identity visibility, and stored daily credit. It SHALL load only the date-bounded session detail needed for that breakdown and remain usable offline. Opening, expanding, or refreshing local presentation SHALL NOT write progress, award XP, enqueue sync, or make external requests. During recomputation it SHALL retain the prior coherent snapshot with an updating state or show an initial loading state.

#### Scenario: Correction is being committed
- **WHEN** session placement and daily credit change together
- **THEN** the breakdown updates from a coherent committed snapshot instead of presenting an intermediate reconciliation difference as a recorded fact

#### Scenario: Local date changes
- **WHEN** midnight changes the requested date
- **THEN** the previous day's rows are not relabelled as today's and the new date's breakdown is loaded

#### Scenario: Offline inspection
- **WHEN** the player expands a cached daily breakdown while offline
- **THEN** its available local evidence appears without any network request or progress mutation
