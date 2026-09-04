## MODIFIED Requirements

### Requirement: Manual admission preserves source safety
A Settings-initiated import SHALL confirm the submitted app id is absent from the configured
account's current `GetOwnedGames` response and is a game according to the Steam Store before
creating a Family Shared row. It SHALL respect existing tracked rows and sticky exclusions. Where
the import proceeds, the system SHALL fetch that game's player achievement data from Steam once and
persist whatever it returns through the same achievement store used by an ordinary sync, so the
imported game's achievement surfaces reflect what was found rather than only a summary of it.

#### Scenario: Current owned-library check passes
- **WHEN** a submitted app id is absent from `GetOwnedGames`, is not tracked or excluded, and the
  Store verifies it as a game
- **THEN** the same Family Shared game shape used by automatic admission is persisted

#### Scenario: Owned, tracked, or excluded
- **WHEN** the submitted app id is owned, already tracked, or excluded
- **THEN** no duplicate or wrongly sourced row is created and the reason is returned to Settings

#### Scenario: No authoritative answer
- **WHEN** either the owned-library request or Store verification cannot provide an authoritative
  answer
- **THEN** nothing is imported

#### Scenario: Probed achievements are persisted
- **WHEN** the post-import achievement fetch returns player achievement data for the imported game
- **THEN** that data is stored the same way a sync would store it, so the game's achievement,
  rarity, and completion surfaces show it immediately, not just a one-time summary message

#### Scenario: Steam returns no usable player data
- **WHEN** the post-import achievement fetch finds no usable player data for the imported game
- **THEN** nothing is persisted, the game is presented with no achievement surface, and this is not
  treated as an error
