## MODIFIED Requirements

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

### Requirement: Per-data-kind freshness
The system SHALL apply freshness windows appropriate to how each kind of achievement data changes:
a long window for a game's achievement schema, a medium window for global unlock percentages, and
play-evidence-driven refresh for per-player unlock state. Refreshing one kind SHALL NOT require
refetching the others.

#### Scenario: Schema served from cache
- **WHEN** a game's per-player achievement state is refreshed and its stored schema is within the
  long window
- **THEN** the stored schema is reused and no schema request is made

#### Scenario: Global percentages served from cache
- **WHEN** a game's per-player achievement state is refreshed and its stored global percentages are
  within the medium window
- **THEN** the stored percentages are reused and no global-percentage request is made

#### Scenario: Stale static data refreshed alongside
- **WHEN** a game's achievements are refreshed and its schema or global percentages are outside
  their window
- **THEN** the stale kind is refetched in that same refresh

#### Scenario: Rarity snapshot unaffected by caching
- **WHEN** global percentages are served from cache at the moment an achievement is first observed
  unlocked
- **THEN** the cached percentage is used for the rarity snapshot, and the snapshot remains stable
  against later drift as specified

## ADDED Requirements

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

### Requirement: Bounded achievement fetch volume
Achievement fetching SHALL be bounded so that its duration is predictable rather than proportional
to the slowest individual request. Requests SHALL carry connect and read timeouts, and concurrent
requests SHALL be limited to a modest fixed number.

#### Scenario: A stalled request does not stall the pass
- **WHEN** an individual achievement request does not respond
- **THEN** it times out and the pass continues with the remaining games

#### Scenario: Concurrency is capped
- **WHEN** many games require refreshing in one pass
- **THEN** no more than a fixed modest number of achievement requests are in flight at once

#### Scenario: Cancellation is honoured
- **WHEN** an achievement pass is cancelled by the system
- **THEN** it stops issuing further requests rather than continuing through its remaining work
