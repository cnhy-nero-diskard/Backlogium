## ADDED Requirements

### Requirement: Achievement watch shares coordinated refresh and merge semantics
The watch SHALL use the same per-account/game request and write coordination as other achievement refreshes. It SHALL preserve non-null rarity snapshots, freshness/timestamp guards, metadata, and retirement rules; it SHALL NOT perform reconciliation retirement on a normal watch tick.

#### Scenario: Concurrent requests
- **WHEN** a watch tick and ordinary refresh target the same account/game
- **THEN** their requests/writes coordinate and stale data cannot overwrite newer committed data

#### Scenario: Retired row remains retired
- **WHEN** a normal watch observation sees incomplete game schema
- **THEN** it does not mass-retire or delete unrelated stored achievements

### Requirement: A watch commit requiring a snapshot defers incomplete rarity data
For an unlocked row lacking an existing non-null snapshot, a watch commit SHALL require a usable global percentage for that row. Missing/invalid required rarity data SHALL defer the whole observation without persistence, event insertion, or baseline advancement. Existing non-null snapshots SHALL remain immutable, and a previously null snapshot MAY be repaired when usable data arrives. This stronger gate SHALL apply to watch commits only.

#### Scenario: Usable zero percentage
- **WHEN** a newly observed unlocked row has a valid global percentage of zero
- **THEN** zero is stored as a valid snapshot

#### Scenario: Already snapshotted unlock
- **WHEN** the player response has no newly unsnapshotted unlock
- **THEN** fresh globals are still attempted, but their failure alone does not block a commit that requires no new snapshot

#### Scenario: Missing data later recovers
- **WHEN** a previously null snapshot obtains a usable percentage on a later watch read
- **THEN** it is filled without treating the repair alone as a new unlock alert

### Requirement: Achievement catalogues are available before play
Opening a visible tracked game's detail SHALL hydrate its missing or stale achievement catalogue regardless of owned/shared source or recorded playtime. The system SHALL cache successful catalogues, including confirmed empty results, for the schema freshness window. Browsing SHALL work independently of live monitoring and SHALL NOT start a library sweep or recurring achievement poll.

#### Scenario: Unplayed owned game
- **WHEN** an owned game with zero recorded playtime is opened without a cached catalogue
- **THEN** only that game's catalogue is requested and persisted for browsing

#### Scenario: Repeat visit within freshness window
- **WHEN** a game with a fresh catalogue and unknown personal progress is reopened
- **THEN** cached objectives remain browsable without repeating catalogue or player requests solely because progress is unknown

#### Scenario: Confirmed empty catalogue is cached
- **WHEN** a successful complete schema reports no achievements and no retained player rows contradict it
- **THEN** the empty catalogue is cached without repeated requests on each visit

#### Scenario: Stale catalogue
- **WHEN** a game's cached catalogue exceeds the schema freshness window and detail is opened online
- **THEN** one targeted hydration refreshes that catalogue while cached content remains visible

#### Scenario: Missing or malformed schema response
- **WHEN** an attempted schema lookup does not provide a usable authoritative catalogue response
- **THEN** catalogue availability stays unknown or last-good and no confirmed-empty cache is written

#### Scenario: Offline opening
- **WHEN** game detail is opened without usable internet
- **THEN** cached content remains available and no fresh data is claimed

### Requirement: Catalogue data does not invent personal achievement progress
The system SHALL retain catalogue availability independently of player progress. Private, no-stats, failed, or contradictory empty player responses SHALL NOT erase a known catalogue or confirm that a game has no achievements. Catalogue-only entries SHALL have unknown personal progress and SHALL NOT contribute earned XP, completion, rarity standing, or unlock events.

#### Scenario: Schema succeeds but player data is unusable
- **WHEN** Steam returns a catalogue but no usable player achievement state
- **THEN** the catalogue is stored without advancing player freshness or replacing last-good progress, and personal progress remains unknown where unconfirmed

#### Scenario: Empty player response contradicts catalogue
- **WHEN** a successful player response is empty but a positive catalogue is available
- **THEN** the catalogue remains available and the player response does not mark the game achievement-free

#### Scenario: Confirmed player progress arrives later
- **WHEN** a later player observation supplies usable achievement states
- **THEN** those states merge through the normal snapshot rules without catalogue hydration producing a historical unlock alert

#### Scenario: Failed schema with empty global percentages
- **WHEN** catalogue fetching fails and global percentages are empty
- **THEN** the game remains of unknown catalogue availability instead of being classified as having no achievements

### Requirement: Detail hydration shares bounded achievement work
Detail hydration SHALL coordinate per account/game with watch, manual, ordinary, reconciliation, and post-play refreshes. It SHALL preserve last-good data, honour offline/rate-limit backoff, avoid overlapping or recomposition-triggered requests, and reject commits invalidated by account, visibility, or library membership changes. Failed data-kind fetches SHALL NOT advance their freshness.

#### Scenario: Detail and watch overlap
- **WHEN** detail hydration and an active watch request the same game
- **THEN** shared requests are coalesced and missing catalogue work is satisfied without accepting incomplete player-only data as catalogue success

#### Scenario: Late response after account replacement or hiding
- **WHEN** a pending detail response finishes after the account changes or its game is hidden or removed
- **THEN** it cannot repopulate that account/game's visible data

#### Scenario: Recomposition and throttling
- **WHEN** a loading screen recomposes or a throttled load is retried
- **THEN** requests remain coalesced and obey the retry deadline

#### Scenario: Detail closes during a shared fetch
- **WHEN** the last detail observer closes while another refresh still needs the same fetch
- **THEN** only the detail subscription is cancelled and the other caller's work remains valid

## MODIFIED Requirements

### Requirement: Tiered achievement refresh
The system SHALL select which games to refresh achievements for based on evidence that the player
has played them, rather than refreshing the entire library on a single wall-clock freshness window.
The system SHALL refresh a game's per-player achievement state when that game shows a playtime
increase in the current sync, SHALL refresh games the player has played recently on every sync, and
SHALL refresh the remainder automatically only during an infrequent reconciliation pass. Targeted detail hydration and confirmed active-game watching SHALL operate independently of this automatic tier selection. The system SHALL NOT
automatically fetch achievements for an owned game with no recorded playtime during routine sync or reconciliation. Opening that game's detail or watching confirmed active play SHALL permit a targeted fetch regardless of recorded playtime. For a family-shared game, whose
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
