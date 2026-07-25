## ADDED Requirements

### Requirement: Provable rarity standing bound
The system SHALL derive, from per-achievement global unlock rates alone, an upper bound on the share
of a game's owners who have unlocked at least as many achievements as the player, and the derived
bound SHALL be mathematically guaranteed rather than estimated.

#### Scenario: Bound derived from unlock rates
- **WHEN** a game has a known total achievement count, a player unlocked count of at least one, and
  known global unlock rates
- **THEN** an upper bound on the share of owners at or above the player's unlocked count is derived
  from those rates alone, with no other data source

#### Scenario: Tightest available bound chosen
- **WHEN** more than one valid bound can be derived
- **THEN** the smallest of them is used

#### Scenario: Bound never exceeds certainty
- **WHEN** a derived bound would exceed the whole population
- **THEN** it is clamped to the whole population

#### Scenario: Full completion
- **WHEN** the player has unlocked every achievement in the game
- **THEN** the bound equals the rarest achievement's unlock rate, which is also the ceiling on the
  share of owners who have completed the game

### Requirement: Bound validity with incomplete rate data
The derivation SHALL remain valid when some achievements have unknown unlock rates, by drawing only on
achievements whose rates are known while still counting every achievement toward the player's missing
count.

#### Scenario: Some rates unknown
- **WHEN** some of a game's achievements have unknown global unlock rates
- **THEN** the bound is derived using only achievements with known rates, and the player's missing
  count is still computed from the game's full achievement total

#### Scenario: Too few known rates
- **WHEN** the number of achievements with known rates is not enough to derive any valid bound
- **THEN** no bound is produced, rather than an unproven one

#### Scenario: No achievements unlocked
- **WHEN** the player has unlocked no achievements
- **THEN** no bound is produced, because none is derivable

### Requirement: Average owner unlock count
The system SHALL derive the average number of achievements an owner of the game has unlocked, from the
same global unlock rates.

#### Scenario: Average derived
- **WHEN** a game's global unlock rates are known
- **THEN** the average number of achievements unlocked per owner is derived from their sum

#### Scenario: Average with incomplete rates
- **WHEN** some unlock rates are unknown
- **THEN** the average is derived from the known rates only
