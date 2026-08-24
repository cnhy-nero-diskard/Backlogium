## ADDED Requirements

### Requirement: A game's source is indicated where it matters
Game detail SHALL indicate that a game is family-shared, and the Library SHALL make the
distinction perceptible without relying on colour alone. The indication SHALL be subordinate to the
game's own identity rather than competing with it, and SHALL be absent for owned games.

#### Scenario: Shared game on game detail
- **WHEN** a family-shared game's detail is viewed
- **THEN** it is identified as played through Family Sharing

#### Scenario: Shared game in the Library
- **WHEN** a family-shared game appears in a Library list
- **THEN** its source is perceptible from the row without depending on colour alone

#### Scenario: Owned games carry no marking
- **WHEN** an owned game is viewed anywhere
- **THEN** no source indication is shown, and it is presented exactly as it is today

#### Scenario: The marking does not dominate
- **WHEN** a family-shared game is presented
- **THEN** its artwork and name remain the primary identity, and the source reads as secondary

### Requirement: Shared games are represented in Analytics
The Analytics screen SHALL account for family-shared games in its totals and SHALL allow their
contribution to be distinguished from that of owned games. Where a metric is undefined for a game
the system SHALL exclude it rather than counting it as zero.

#### Scenario: Shared playtime included
- **WHEN** analytics are computed over a period in which a family-shared game was played
- **THEN** its tracked playtime is included in the totals

#### Scenario: Contribution distinguishable
- **WHEN** the player views analytics
- **THEN** the contribution of family-shared games can be told apart from that of owned games

#### Scenario: Undefined metrics exclude rather than zero
- **WHEN** a metric cannot be computed for a game, such as achievement completion where Steam
  reports no achievements
- **THEN** that game is excluded from the metric rather than contributing a zero that would lower
  an average

### Requirement: Achievement surfaces follow what Steam reports
Where Steam reports achievement progress for a family-shared game, the existing achievement,
rarity, and standing surfaces SHALL present it as for any other game. Where Steam reports no
achievement data, the game SHALL be presented without an achievement surface rather than with an
empty one.

#### Scenario: Achievements available for a shared game
- **WHEN** Steam reports achievement progress for a family-shared game
- **THEN** achievements, rarity tiers, and rarity standing are presented as for an owned game

#### Scenario: No achievement data
- **WHEN** Steam reports no achievement data for a family-shared game
- **THEN** no achievement surface is shown for it, and no empty or zeroed surface appears in its
  place

#### Scenario: Rarity XP unaffected
- **WHEN** achievements are reported for a family-shared game and unlocked
- **THEN** they contribute rarity-tiered XP on the same terms as an owned game's
