# gamification

The gamification engine is pure, platform-agnostic logic: it takes tracked playtime
plus configuration and returns XP/level, goal progress, daily-quest status, and
streaks. It performs no I/O, no networking, and no persistence — callers supply
inputs and persist outputs.

## ADDED Requirements

### Requirement: Tunable rule configuration
The engine SHALL expose all rule constants — XP rate, level curve, daily-quest
threshold, quest mode, and streak grace — as configuration, so rules can change
without altering call sites or stored schema.

#### Scenario: Overriding a constant
- **WHEN** the caller supplies a configuration with a non-default XP rate or quest threshold
- **THEN** all subsequent computations use the supplied values

#### Scenario: Default configuration
- **WHEN** no overrides are supplied
- **THEN** the engine uses documented defaults (1 XP per minute; quest = 30 min of any game)

### Requirement: XP and levels
The engine SHALL compute total XP from tracked minutes at the configured rate and
derive a level from total XP using a documented monotonic curve, along with progress
toward the next level.

#### Scenario: Earning XP from tracked minutes
- **WHEN** the engine is given a total of M tracked minutes
- **THEN** total XP is `M × rate` (default 1 XP per minute)

#### Scenario: Deriving level and progress
- **WHEN** total XP is provided
- **THEN** the engine returns the current level, the XP accumulated within that level, and the XP required to reach the next level

#### Scenario: Level boundary
- **WHEN** total XP exactly equals a level threshold
- **THEN** the higher level is reported with zero progress into it

### Requirement: Goal-game progress
The engine SHALL compute progress for a goal game as the supplied playtime divided by
its target, clamped to the range 0.0–1.0. The caller decides which playtime to supply;
the engine is agnostic.

#### Scenario: Partial progress
- **WHEN** a goal game has P minutes of playtime against a target of T minutes with P < T
- **THEN** progress is reported as `P / T`

#### Scenario: Completed goal
- **WHEN** the supplied playtime meets or exceeds the target
- **THEN** progress is reported as `1.0`

### Requirement: Daily quest evaluation
The engine SHALL evaluate whether a given day's quest is met, defaulting to "at least
30 minutes of any game," with the threshold and an "any game vs goal games only" mode
supplied by configuration.

#### Scenario: Quest met
- **WHEN** a day's qualifying playtime reaches the configured threshold
- **THEN** the day's quest is reported as met

#### Scenario: Goal-only mode
- **WHEN** the quest mode is goal-games-only
- **THEN** only playtime on goal games counts toward the threshold

#### Scenario: Quest unmet
- **WHEN** a day's qualifying playtime is below the threshold
- **THEN** the day's quest is reported as unmet

### Requirement: Streak computation
The engine SHALL compute current and longest streaks from an ordered set of per-day
quest results, counting consecutive met days and breaking on the first unmet day,
honoring the configured grace allowance.

#### Scenario: Extending a streak
- **WHEN** consecutive days each meet the quest
- **THEN** the current streak equals the count of those days and the longest streak is at least that value

#### Scenario: Breaking a streak
- **WHEN** a day is unmet beyond the configured grace
- **THEN** the current streak resets to zero for days following the break

#### Scenario: Longest preserved after a break
- **WHEN** a streak breaks after reaching length N
- **THEN** the longest streak remains N even as the current streak resets
