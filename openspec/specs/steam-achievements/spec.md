## Purpose

Fetches every library game's per-player Steam achievement unlock data and global unlock
percentages, gating that fetch by data freshness so routine syncs stay cheap. Persists
achievements keyed by game and achievement id, capturing a first-unlock rarity snapshot
that never drifts on later syncs even as the live global percentage changes. Feeds
unlocked achievements — using each one's rarity snapshot — into the gamification engine's
XP recompute so they contribute tiered XP to the player's total.

## Requirements

### Requirement: Fetch Steam achievement data
The system SHALL fetch, from Steam, each library game's per-player achievement unlock state and the
global unlock percentage for each of that game's achievements, and MAY fetch the game's achievement
schema for display names and icons or serve that schema from previously stored data.

#### Scenario: Fetching a game's achievements
- **WHEN** a library game is selected for an achievement refresh
- **THEN** the system requests that game's per-player unlock state and global unlock percentages
  from Steam and stores the results

#### Scenario: Game has no achievements
- **WHEN** a fetched game exposes no achievements
- **THEN** the system records that the game has no achievements and does not treat it as an error

#### Scenario: Achievement fetch fails for a game
- **WHEN** a game's achievement request fails (private profile, no stats, or a transport error)
- **THEN** that game's achievement fetch is skipped without failing the overall sync, and any
  previously stored achievement data for that game is left intact

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

### Requirement: Per-data-kind freshness
The system SHALL apply freshness windows appropriate to how each kind of achievement data changes: a
long window for a game's achievement schema, and play-evidence-driven refresh for per-player unlock
state. Global unlock percentages SHALL be fetched together with per-player unlock state rather than
served from a window of their own, because other capabilities derive current-population figures from
them. Refreshing one kind SHALL NOT require refetching the others.

#### Scenario: Schema served from cache
- **WHEN** a game's per-player achievement state is refreshed and its stored schema is within the
  long window
- **THEN** the stored schema is reused and no schema request is made

#### Scenario: Stale schema refreshed alongside
- **WHEN** a game's achievements are refreshed and its stored schema is outside the long window
- **THEN** the schema is refetched in that same refresh

#### Scenario: Global percentages stay current
- **WHEN** a game's per-player achievement state is refreshed
- **THEN** its global unlock percentages are fetched in that same refresh and the stored current
  percentages are updated, so figures derived from the live percentages describe the owner
  population as it currently stands

#### Scenario: Schema caching does not affect the rarity snapshot
- **WHEN** an achievement is first observed unlocked while its game's schema is served from cache
- **THEN** the rarity snapshot is taken from the freshly fetched global percentage as specified, and
  remains stable against later drift

### Requirement: Deferred achievement reconciliation
The system SHALL periodically reconcile achievement data across the whole library on an infrequent
schedule, separately from the periodic playtime sync, so that unlocks not detectable from playtime
evidence are eventually captured. This pass SHALL run under device conditions that make its
duration inconsequential, SHALL NOT delay or block the periodic sync, and SHALL be resumable across
runs so a partial pass makes forward progress.

#### Scenario: Reconciliation runs when conditions allow
- **WHEN** the reconciliation interval has elapsed and the device is charging on an unmetered
  network
- **THEN** the pass runs and refreshes library games whose achievement data is outside its
  reconciliation window

#### Scenario: Conditions unmet
- **WHEN** the reconciliation interval has elapsed but the device conditions are unmet
- **THEN** the pass is deferred, and the periodic sync continues on its own schedule unaffected

#### Scenario: Reconciliation does not block the sync
- **WHEN** a reconciliation pass is in progress
- **THEN** a periodic or manual sync can still run and complete without waiting for it

#### Scenario: Partial pass makes progress
- **WHEN** a reconciliation pass is interrupted before covering every game
- **THEN** the games already refreshed are recorded as such, and the next pass continues with those
  not yet covered rather than restarting

#### Scenario: Player-initiated reconciliation
- **WHEN** the player explicitly requests a full achievement refresh
- **THEN** a reconciliation pass is enqueued without waiting for the interval or the device
  conditions

#### Scenario: Reconciliation after a restore
- **WHEN** a restore from backup completes
- **THEN** a reconciliation pass is enqueued, so a library whose achievement data the restore could
  only partially supply converges as soon as conditions allow rather than waiting out the interval

### Requirement: Bounded achievement fetch volume
Achievement fetching SHALL be bounded so that its duration is predictable rather than proportional
to the slowest individual request. Requests SHALL carry connect and read timeouts, and SHALL be
issued serially rather than concurrently.

#### Scenario: A stalled request does not stall the pass
- **WHEN** an individual achievement request does not respond
- **THEN** it times out and the pass continues with the remaining games

#### Scenario: Requests do not burst
- **WHEN** many games require refreshing in one pass
- **THEN** the pass issues one achievement request at a time rather than several at once

#### Scenario: Cancellation is honoured
- **WHEN** an achievement pass is cancelled by the system
- **THEN** it stops issuing further requests rather than continuing through its remaining work

### Requirement: Persist achievements with a first-unlock rarity snapshot
The system SHALL persist each achievement keyed by its game and achievement id, storing its
unlock state, unlock time when available, the current global unlock percentage, and a rarity
percentage snapshotted at the first sync that observes the achievement as unlocked with a
known global percentage. The snapshot SHALL NOT change on later syncs.

#### Scenario: Snapshot taken at first observed unlock
- **WHEN** a sync first observes an achievement as unlocked and a global unlock percentage is available
- **THEN** the system stores that percentage as the achievement's rarity snapshot

#### Scenario: Snapshot is stable against later drift
- **WHEN** a later sync reports a different global unlock percentage for an already-snapshotted achievement
- **THEN** the stored rarity snapshot is unchanged, while the current global percentage is updated for display

#### Scenario: Still-locked achievement has no snapshot
- **WHEN** an achievement has never been observed unlocked
- **THEN** it has no rarity snapshot

### Requirement: Retain achievement descriptions
The achievement fetch SHALL retain each achievement's description and its hidden flag from Steam's
achievement schema, so the UI can present them without an additional network call.

#### Scenario: Description stored
- **WHEN** a game's achievement schema is fetched
- **THEN** each achievement's description and hidden flag are stored alongside its display name and
  icon

#### Scenario: Description absent from the schema
- **WHEN** Steam supplies no description for an achievement
- **THEN** no description is stored for it and the fetch does not fail

#### Scenario: Existing achievements not force-refreshed
- **WHEN** achievement rows were stored before descriptions were retained
- **THEN** they are not eagerly re-fetched, and their descriptions populate on the game's next
  natural schema fetch

### Requirement: Feed achievement XP into gamification
The system SHALL build the gamification engine's achievement inputs from stored
achievements — using the rarity snapshot as each achievement's rarity input — and pass them
into the XP recompute so unlocked achievements contribute XP to the player's total.

#### Scenario: Unlocked achievements contribute XP
- **WHEN** the gamification values are recomputed and the player has unlocked achievements with a rarity snapshot
- **THEN** the recompute includes those achievements so their tiered XP is added to the player's total XP

#### Scenario: Locked or un-snapshotted achievements contribute nothing
- **WHEN** an achievement is locked, or unlocked but without a rarity snapshot
- **THEN** it contributes zero XP to the recompute

#### Scenario: XP uses the snapshot, not the live percentage
- **WHEN** an achievement's current global percentage differs from its rarity snapshot
- **THEN** its XP contribution is computed from the snapshot, so already-earned XP does not change

### Requirement: Refreshes of the same game are serialized
The system SHALL ensure that two achievement refreshes for the same game cannot be in
flight at once, so that a merge computed from older state cannot be committed after a
merge computed from newer state.

#### Scenario: Sync and reconciliation overlap
- **WHEN** a scheduled reconciliation and a normal sync would each refresh the same
  game's achievements
- **THEN** only one refresh runs, or they run strictly one after the other

#### Scenario: Newer observation is not overwritten
- **WHEN** two refreshes for one game complete out of the order they started in
- **THEN** the stored unlock state reflects the newer observation, not the older one

#### Scenario: Rarity snapshot invariant holds under concurrency
- **WHEN** refreshes for one game overlap in time
- **THEN** the first-unlock rarity snapshot is still written exactly once and is not
  replaced by a later observation

### Requirement: Achievements Steam stops returning are retired, not deleted
When a refresh no longer includes an achievement that is stored locally, the system SHALL
mark that achievement as no longer offered and exclude it from counts, displayed totals,
and experience, while retaining its stored row and its first-unlock rarity snapshot.
Rows SHALL NOT be deleted on absence from a single response.

#### Scenario: Achievement absent from a response
- **WHEN** a full reconciliation for a game returns a set that omits a stored achievement
- **THEN** that achievement is marked as no longer offered and stops contributing to
  counts, totals, and experience

#### Scenario: Rarity snapshot survives retirement
- **WHEN** an achievement is marked as no longer offered
- **THEN** its first-unlock rarity snapshot remains stored, because that value cannot be
  recovered from any source once discarded

#### Scenario: Achievement returns in a later response
- **WHEN** a later refresh includes an achievement previously marked as no longer offered
- **THEN** the mark is cleared and the achievement contributes to counts again, using its
  retained snapshot

#### Scenario: Absence during a partial refresh does not retire
- **WHEN** a refresh covers only part of the library, or does not represent a full view of
  a game's achievement set
- **THEN** no achievement is marked as no longer offered on the basis of that refresh

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
