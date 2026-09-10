## MODIFIED Requirements

### Requirement: Tiered achievement refresh
The system SHALL select which games to refresh achievements for based on evidence that the player
has played them, rather than refreshing the entire library on a single wall-clock freshness window.
The system SHALL refresh a game's per-player achievement state when that game shows a playtime
increase in the current sync, SHALL refresh games the player has played recently on every sync, and
SHALL refresh the remainder only during an infrequent reconciliation pass. The system SHALL NOT
fetch achievements for an owned game with no recorded playtime. For a family-shared game, whose
locally tracked playtime reflects only what the app itself observed and structurally undercounts
real play, the absence of locally tracked playtime SHALL NOT by itself exclude it from achievement
fetching: a family-shared game becomes eligible for a one-time fetch immediately upon admission,
regardless of its tracked playtime, and an already-admitted family-shared game with no stored
achievement data becomes eligible for a bounded backfill pass on the same terms as other missing
data.

#### Scenario: Game played since the last sync
- **WHEN** a sync observes an increase in a game's total playtime
- **THEN** that game's per-player achievement state is refreshed in that sync

#### Scenario: Recently played game without a new delta
- **WHEN** a game shows recent play activity but no playtime increase in the current sync
- **THEN** its per-player achievement state is still refreshed, so an unlock that Steam reported
  after the playtime increase is not missed

#### Scenario: Game not played recently
- **WHEN** an owned game shows neither a playtime increase nor recent play activity
- **THEN** it is not refreshed during that sync and is left to the reconciliation pass

#### Scenario: Never-played game
- **WHEN** an owned game has no recorded playtime
- **THEN** no achievement request is made for it

#### Scenario: Missing data is still fetched
- **WHEN** a game has recorded playtime but no stored achievement data at all
- **THEN** it is eligible for fetching regardless of tier, so a newly added library game is not
  withheld until the next reconciliation pass

#### Scenario: Missing-data eligibility is bounded per sync
- **WHEN** more games lack stored achievement data than a single sync may cover, as after a first
  install or a restore from backup
- **THEN** a bounded number of them are fetched in that sync, oldest-first, and the remainder stay
  eligible for subsequent syncs and the reconciliation pass, so inline work does not scale with the
  library

#### Scenario: A family-shared game is fetched on admission regardless of tracked playtime
- **WHEN** a family-shared game is admitted, whether by manual import or automatic presence-based
  admission, and it has no locally tracked playtime
- **THEN** it is still made eligible for a one-time achievement fetch, because Steam's own
  `GetPlayerAchievements` for a shared game does not depend on Backlogium having observed a session

#### Scenario: An already-admitted family-shared game with no stored achievement data is backfilled
- **WHEN** a family-shared game already in the library has no stored achievement sync data,
  including one that was previously classified as never-played on the basis of zero locally tracked
  playtime
- **THEN** it becomes eligible for a bounded backfill fetch, so a game completed before Backlogium
  ever observed it is not permanently excluded
