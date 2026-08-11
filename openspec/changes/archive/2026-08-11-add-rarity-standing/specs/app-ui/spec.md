## ADDED Requirements

### Requirement: Rarity Standing section
The achievements UI SHALL present a Rarity Standing section stating the player's provable standing
among owners of that game, their unlocked count against the average owner's, and the caveat that the
population includes owners who never played the game.

#### Scenario: Standing shown
- **WHEN** a game has achievements, the player has unlocked at least one, and a bound is derivable
- **THEN** the section presents the bound as a ceiling on the share of owners at or above the player's
  count, alongside the player's count, the game's total, and the average owner's count

#### Scenario: Population caveat always present
- **WHEN** the section is shown
- **THEN** it states that the figures are based on all Steam owners, including unplayed copies

#### Scenario: Phrased as a ceiling, never as a rank
- **WHEN** the bound is presented
- **THEN** it is phrased as an upper bound — a standing of "or better" — and never as an exact
  percentile or rank

#### Scenario: Uninformative bound suppressed
- **WHEN** the derived bound is at or above half of all owners
- **THEN** the bound is not presented, and only the player's count against the average owner's count
  is shown

#### Scenario: No bound derivable
- **WHEN** the player has unlocked no achievements, or too few unlock rates are known to derive a
  bound
- **THEN** the bound is not presented, and only the player's count against the average owner's count
  is shown

#### Scenario: Game without achievement data
- **WHEN** a game has no achievements, or no achievement data has been stored for it
- **THEN** the section is not shown at all

### Requirement: Rarity Standing rounding never overstates
A presented bound SHALL never be tighter than the derived one, and SHALL remain legible at very small
values.

#### Scenario: Rounded away from zero
- **WHEN** a derived bound is displayed at reduced precision
- **THEN** it is rounded away from zero, so the displayed figure is still an upper bound

#### Scenario: Precision by magnitude
- **WHEN** a bound below one tenth of all owners is displayed
- **THEN** it is shown to one decimal place; larger bounds are shown as whole numbers

#### Scenario: Extremely small bound
- **WHEN** a derived bound is smaller than the smallest displayable value
- **THEN** it is presented at that smallest displayable value rather than as zero
