# gamification

The gamification engine is pure, platform-agnostic logic: it takes tracked playtime
plus configuration and returns XP/level, goal progress, daily-quest status, and
streaks. It performs no I/O, no networking, and no persistence — callers supply
inputs and persist outputs.

## Requirements

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
The engine SHALL compute XP per game from that game's tracked minutes with diminishing
returns relative to its HowLongToBeat completionist-average length, sum XP across all
supplied games into a total, and derive a level from that total using a documented
monotonic curve, along with progress toward the next level.

#### Scenario: Early playtime earns near the base rate
- **WHEN** a game's tracked minutes are small relative to its completionist-average length
- **THEN** the XP earned is close to the flat `minutes × rate` amount, with only a small reduction from the taper

#### Scenario: Playtime at the completionist average earns a very small marginal rate
- **WHEN** a game's tracked minutes exactly equal its completionist-average length
- **THEN** the marginal XP rate at that point is reduced to a documented small fraction of the base rate, per the configured decay curve

#### Scenario: Playtime at or beyond twice the completionist average earns no further XP
- **WHEN** a game's tracked minutes reach or exceed twice its completionist-average length
- **THEN** no additional XP is earned for minutes at or beyond that point, and total XP for that game does not increase further

#### Scenario: Missing completionist-average data
- **WHEN** a game has no completionist-average length available
- **THEN** that game's XP is computed at the flat, uncapped rate (`minutes × rate`), with no taper applied

#### Scenario: Total XP across a library
- **WHEN** the engine is given tracked minutes and completionist-average lengths for multiple games
- **THEN** total XP is the sum of each game's individually computed XP

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

#### Scenario: Grace forgives a gap without crediting it
- **WHEN** an unmet day falls within the configured grace allowance and is followed by met days
- **THEN** the current streak is preserved across the gap and increments only for the met
  days — the forgiven day itself does not add to the streak — and a subsequent met day
  restores the full grace allowance

### Requirement: Achievement rarity tiers
The engine SHALL classify an achievement into one of five rarity tiers — `COMMON`,
`UNCOMMON`, `RARE`, `EPIC`, `LEGENDARY` — based solely on its global unlock percentage,
with boundary values assigned to the more-common (higher) tier.

#### Scenario: Common achievement
- **WHEN** an achievement's global unlock percentage is 50% or higher
- **THEN** it is classified as `COMMON`

#### Scenario: Uncommon achievement
- **WHEN** an achievement's global unlock percentage is at least 20% but below 50%
- **THEN** it is classified as `UNCOMMON`

#### Scenario: Rare achievement
- **WHEN** an achievement's global unlock percentage is at least 5% but below 20%
- **THEN** it is classified as `RARE`

#### Scenario: Epic achievement
- **WHEN** an achievement's global unlock percentage is at least 1% but below 5%
- **THEN** it is classified as `EPIC`

#### Scenario: Legendary achievement
- **WHEN** an achievement's global unlock percentage is below 1%
- **THEN** it is classified as `LEGENDARY`

#### Scenario: Exact boundary values
- **WHEN** an achievement's global unlock percentage is exactly 50%, 20%, 5%, or 1%
- **THEN** it is classified into the more-common (higher) tier for that boundary
  (`COMMON` at exactly 50%, `UNCOMMON` at exactly 20%, `RARE` at exactly 5%, `EPIC` at
  exactly 1%)

### Requirement: Tunable per-tier XP awards
The engine SHALL expose the XP awarded for each rarity tier as configuration, with
documented defaults, so tier payouts can change without altering call sites or stored
schema.

#### Scenario: Default tier XP values
- **WHEN** no overrides are supplied
- **THEN** the engine uses documented default XP values per tier, strictly increasing
  from `COMMON` through `LEGENDARY`

#### Scenario: Overriding tier XP values
- **WHEN** the caller supplies a configuration with non-default per-tier XP values
- **THEN** all subsequent achievement XP computations use the supplied values

### Requirement: Achievement XP computation
The engine SHALL compute XP from a supplied list of achievements as the sum of each
unlocked achievement's tiered XP value; locked achievements SHALL NOT contribute XP.

#### Scenario: Single unlocked achievement
- **WHEN** the engine is given one unlocked achievement with a known global unlock percentage
- **THEN** the returned achievement XP equals the configured XP for that achievement's tier

#### Scenario: Locked achievement contributes nothing
- **WHEN** the engine is given an achievement marked as not unlocked
- **THEN** that achievement contributes zero XP regardless of its global unlock percentage

#### Scenario: Unlocked achievement with no global stat
- **WHEN** an unlocked achievement has no global unlock percentage available (null)
- **THEN** it cannot be classified into a tier and contributes zero XP, rather than
  defaulting to any tier's award

#### Scenario: Genuinely zero-percent achievement
- **WHEN** an unlocked achievement has a global unlock percentage of exactly 0.0
- **THEN** it is classified as `LEGENDARY` and awarded the legendary XP value

#### Scenario: Multiple achievements across tiers
- **WHEN** the engine is given several unlocked achievements spanning different rarity tiers
- **THEN** the returned achievement XP equals the sum of each achievement's tiered XP value

#### Scenario: Empty achievement list
- **WHEN** the engine is given an empty list of achievements
- **THEN** the returned achievement XP is zero

### Requirement: Achievement XP combines with playtime XP
The engine SHALL combine achievement XP additively with playtime-derived XP into a
single total XP value that drives the existing level curve, without introducing a
separate achievement-only level or progress bar.

#### Scenario: Combined total XP
- **WHEN** the engine is given both per-game playtime inputs and a list of unlocked achievements
- **THEN** the resulting total XP equals the summed per-game playtime XP plus achievement XP,
  and level and progress-into-level are derived from that combined total using the
  existing level curve

#### Scenario: No achievements supplied
- **WHEN** the engine is given per-game playtime inputs and no achievements (or omits the
  achievements argument)
- **THEN** the resulting total XP equals the summed per-game playtime XP alone, unchanged
  from the base engine's existing behavior
