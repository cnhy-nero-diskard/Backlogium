## Purpose
Allocate current engine XP to known activity dates and an explicit undated remainder while preserving existing totals and rules.

## ADDED Requirements

### Requirement: Daily playtime XP is a cumulative engine difference
A day's playtime XP SHALL be the difference between existing per-game cumulative engine contributions through and before that date under current safe rules/HLTB inputs. The input SHALL include imported/manual/undated engine-bearing minutes and all prior dated sessions, with the same wider-type summation and per-game input clamp as the total engine. It SHALL NOT multiply that day's minutes by a separate rate.

#### Scenario: Prior history outside viewport
- **WHEN** a game's earlier minutes precede the displayed 30-day window
- **THEN** the first visible day's marginal XP still uses that cumulative history

#### Scenario: Game reaches taper limit
- **WHEN** a game's daily increment crosses its taper zero point
- **THEN** the existing engine credits only its marginal contribution

#### Scenario: Input saturation
- **WHEN** cumulative minutes exceed the engine's supported input bound
- **THEN** clamping each cumulative engine input preserves nonnegative marginal credit and reconciliation

### Requirement: Achievement XP uses the total engine's current contributing rows
Date attribution SHALL partition the same active unlocked achievement rows and frozen rarity snapshots used by the current total engine. Valid unlock dates SHALL determine date buckets; absent dates SHALL contribute to the undated remainder. Missing snapshots SHALL use the engine's zero credit without pretending the observation is complete.

#### Scenario: Retired or hidden achievement
- **WHEN** a row is excluded from the current XP inputs
- **THEN** it is also excluded from the attribution series

#### Scenario: Snapshot absent
- **WHEN** an unlocked row lacks a rarity snapshot
- **THEN** its contribution matches the existing engine and no rarity value is invented

### Requirement: Dated and undated XP reconcile over one coherent snapshot
All-date attributed XP plus the explicit undated remainder SHALL equal the existing engine total computed from the same contributing snapshot and rules. Contributors without current Library rows SHALL not be dropped. A stale persisted total SHALL be disclosed as updating rather than silently absorbed as an invented undated adjustment.

#### Scenario: Imported and manual credit
- **WHEN** a library includes imported minutes, a shared-game estimate, and dated sessions
- **THEN** the undated base plus marginal dated series equals the engine total

#### Scenario: Orphan contributor
- **WHEN** a contributing session or achievement has no current Library row
- **THEN** its XP remains accounted for with fallback identity

#### Scenario: Rules change
- **WHEN** current rules or HLTB inputs change
- **THEN** the projection recomputes coherently and remains labelled current-rule attribution

### Requirement: Daily XP is a read projection rather than historical awarded XP
The series SHALL be derived without storing per-day XP or changing quest/streak/progress delivery. Presentation SHALL identify it as XP attributed under current rules. Full contributing history SHALL be available for cumulative replay, while visible detail remains bounded and derived results are reused across viewport changes.

#### Scenario: Unlock before tracking
- **WHEN** a dated old unlock contributes to the total
- **THEN** the day can show attributed achievement XP with unknown playtime without claiming when XP was awarded

#### Scenario: Opening and paging
- **WHEN** the player reads Home or History attribution
- **THEN** no earned recompute, celebration, data write, or external fetch occurs merely from viewing
