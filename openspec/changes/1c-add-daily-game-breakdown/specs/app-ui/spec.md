## ADDED Requirements

### Requirement: Today's quest expands into an evidence-aware game breakdown
Home SHALL offer an initially collapsed per-game minute breakdown inside Today's quest while preserving the compact collapsed card. The expanded content SHALL identify its date, visible recorded subtotal, authoritative credited total when available, and any reconciliation or unavailable allocation required by the daily-activity-breakdown capability. It SHALL not change the quest result or imply that recorded session minutes are exact elapsed play.

#### Scenario: Breakdown expanded
- **WHEN** the player expands Today's quest on a multi-game day
- **THEN** each visible game's recorded minutes appear and the credit comparison explains how they relate to the card's quest total

#### Scenario: No recorded activity
- **WHEN** today's breakdown contains neither visible sessions nor stored credit
- **THEN** Home shows a compact no-recorded-activity state rather than fabricating games

#### Scenario: Per-game allocation unavailable
- **WHEN** recorded credit exists without per-game session evidence
- **THEN** the expanded card explains the unavailable allocation and retains the authoritative quest total

### Requirement: Daily breakdown actions remain accessible and account safe
The expansion control SHALL announce its purpose and expanded state, and visible game rows SHALL offer named detail actions with accessible touch targets. Missing or no-longer-visible games SHALL receive a clear unavailable-navigation state rather than opening an unrelated game. Home SHALL use localized dates, quantities, durations, and textual qualifiers that remain readable with larger fonts and in both themes. Date changes SHALL collapse the new day's breakdown; changing accounts SHALL discard the old account's expanded content and rows.

#### Scenario: Detail navigation and return
- **WHEN** the player opens a visible game from the expanded breakdown and returns on the same account and date
- **THEN** the same day's breakdown remains expanded with current local data

#### Scenario: Target becomes unavailable
- **WHEN** a game is hidden or removed before its detail action executes
- **THEN** Home provides a clear unavailable result and exposes no hidden game detail

#### Scenario: Large-font or screen-reader use
- **WHEN** the player inspects the card with larger fonts or assistive technology
- **THEN** game names, minutes, reconciliation wording, and expansion/navigation actions remain understandable without color-only cues

#### Scenario: Account or date changes
- **WHEN** the active account or current local date changes
- **THEN** stale rows are cleared or replaced under the correct identity and the new breakdown starts collapsed
