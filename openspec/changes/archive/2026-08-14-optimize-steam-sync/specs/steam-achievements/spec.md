## ADDED Requirements

### Requirement: Tiered achievement refresh
The system SHALL select which games to refresh achievements for based on evidence that the player
has played them, rather than refreshing the entire library on a single wall-clock freshness window.
The system SHALL refresh a game's per-player achievement state when that game shows a playtime
increase in the current sync, SHALL refresh games the player has played recently on every sync, and
SHALL refresh the remainder only during an infrequent reconciliation pass. The system SHALL NOT
fetch achievements for a game with no recorded playtime.

#### Scenario: Game played since the last sync
- **WHEN** a sync observes an increase in a game's total playtime
- **THEN** that game's per-player achievement state is refreshed in that sync

#### Scenario: Recently played game without a new delta
- **WHEN** a game shows recent play activity but no playtime increase in the current sync
- **THEN** its per-player achievement state is still refreshed, so an unlock that Steam reported
  after the playtime increase is not missed

#### Scenario: Game not played recently
- **WHEN** a game shows neither a playtime increase nor recent play activity
- **THEN** it is not refreshed during that sync and is left to the reconciliation pass

#### Scenario: Never-played game
- **WHEN** a game has no recorded playtime
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

## MODIFIED Requirements

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

## REMOVED Requirements

### Requirement: Freshness-gated achievement sync
**Reason**: Superseded by "Tiered achievement refresh" above. This requirement mandated the
maximally expensive strategy available — *"fetch achievements for every game in the library,
regardless of play history or goal tagging"* — gated only by a single wall-clock window, which is
the whole cost this change exists to remove. Its two concerns are re-homed rather than dropped:
deciding what to refresh is now specified by "Tiered achievement refresh", and how long each kind of
data stays valid by "Per-data-kind freshness". Retaining it would leave the merged spec asserting
both that every game is fetched regardless of play history and that games with no recorded playtime
are never fetched.
