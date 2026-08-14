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
