# Steam Achievements Delta

## ADDED Requirements

### Requirement: New admissions receive bounded achievement hydration
A committed new owned-game admission after initial library confirmation SHALL become eligible for one targeted achievement hydration cycle regardless of recorded playtime. Automatic shared-game admissions SHALL receive the same bounded scheduling guarantee. Manual shared admission SHALL continue to use its existing targeted fetch.

#### Scenario: New owned game with zero playtime
- **WHEN** a confirmed library poll commits a genuinely new game after the initial baseline
- **THEN** the game becomes eligible promptly after the library commit without requiring a detail visit or future play

#### Scenario: Non-admission writes
- **WHEN** initial setup, restore, historical correction, reappearance of an already-known game, or shared-to-owned conversion populates or changes a library row
- **THEN** the operation does not create a new admission hydration cycle

#### Scenario: Large set of new games
- **WHEN** more admissions are eligible than one drain can process
- **THEN** a bounded subset is processed in admission order and the remaining valid admissions stay eligible

### Requirement: Admission hydration preserves account and achievement evidence
Admission work SHALL survive restart, share per-game coordination with other achievement refresh paths, and respect request limits and bounded backoff. It SHALL preserve last-good data and distinguish confirmed empty, unavailable, and failed observations.

#### Scenario: Concurrent fetch succeeds
- **WHEN** an in-flight detail, watch, or sync fetch satisfies the admission hydration for the same account and app
- **THEN** one authoritative result is merged and redundant admission work is retired without duplicating XP or events

#### Scenario: Transient failure or throttling
- **WHEN** Steam temporarily fails or asks the client to retry later
- **THEN** the previous data remains visible and retry follows the bounded schedule and provider wait

#### Scenario: Obsolete target
- **WHEN** the game is hidden, removed, or no longer belongs to the initiating account before completion
- **THEN** no stale result is committed and no new admission requests are made for that obsolete work

#### Scenario: First observation
- **WHEN** the admission fetch returns personal progress already earned before Backlogium observed the game
- **THEN** achievement data is persisted according to existing rarity rules and the observation does not replay old unlock notifications

## MODIFIED Requirements

### Requirement: Tiered achievement refresh
The system SHALL select which games to refresh achievements for based on evidence that the player
has played them, rather than refreshing the entire library on a single wall-clock freshness window.
The system SHALL refresh a game's per-player achievement state when that game shows a playtime
increase in the current sync, SHALL refresh games the player has played recently on every sync, and
SHALL refresh the remainder automatically only during an infrequent reconciliation pass. Targeted detail hydration and confirmed active-game watching SHALL operate independently of this automatic tier selection. The system SHALL NOT
automatically fetch achievements for an owned game with no recorded playtime during routine sync or reconciliation solely because it is present in the library. Committed new admissions SHALL use the targeted admission policy. Opening that game's detail or watching confirmed active play SHALL permit a targeted fetch regardless of recorded playtime. For a family-shared game, whose
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
- **WHEN** an owned game shows neither a playtime increase nor recent play activity and is outside the targeted admission policy
- **THEN** it is not refreshed during that sync and is left to the reconciliation pass

#### Scenario: Never-played game
- **WHEN** an owned game has no recorded playtime and routine sync or reconciliation runs
- **THEN** no automatic achievement request is made for it solely because it is in the library

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

#### Scenario: Inspecting or playing a never-played game
- **WHEN** the player opens an unplayed game's detail or eligible monitoring confirms that game is running
- **THEN** the targeted request is not blocked by its zero recorded playtime
