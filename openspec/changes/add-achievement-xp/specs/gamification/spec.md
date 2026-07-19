## ADDED Requirements

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
- **WHEN** the engine is given both tracked minutes and a list of unlocked achievements
- **THEN** the resulting total XP equals playtime XP plus achievement XP, and level and
  progress-into-level are derived from that combined total using the existing level
  curve

#### Scenario: No achievements supplied
- **WHEN** the engine is given tracked minutes and no achievements (or omits the
  achievements argument)
- **THEN** the resulting total XP equals playtime XP alone, unchanged from the base
  engine's existing behavior
