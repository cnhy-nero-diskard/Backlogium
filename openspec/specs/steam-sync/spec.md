# steam-sync

## Purpose

Defines Steam integration for the Android app: polling the Steam Web API on a
periodic background schedule using configured credentials, synthesizing play
sessions by diffing reported playtime across polls, establishing a baseline on
first sync so historical playtime is not misread as new sessions, and surfacing
sync failures without discarding the last good data.

## Requirements

### Requirement: Steam credential configuration
The system SHALL read the Steam Web API key and SteamID64 from an encrypted in-app credential
store (populated by the in-app onboarding flow), expose them through a credentials repository, and
SHALL NOT hardcode or commit these values. On first access, when the store is empty and
`BuildConfig` carries non-blank values, the system SHALL seed the store from `BuildConfig` once;
after seeding the encrypted store is the source of truth. All Steam requests SHALL obtain the key
and SteamID from the credentials repository rather than reading `BuildConfig` directly.

#### Scenario: Credentials present
- **WHEN** the encrypted store holds a Steam API key and SteamID64
- **THEN** the values are exposed through the credentials repository and used for all Steam requests

#### Scenario: Credentials seeded from build config
- **WHEN** the store is empty and `steam.apiKey`/`steam.steamId` were provided at build time
- **THEN** the values are imported into the encrypted store once and used thereafter as the stored
  credentials

#### Scenario: Credentials missing
- **WHEN** no credentials are stored and none can be seeded
- **THEN** the app treats itself as not configured and presents the onboarding flow instead of
  crashing

### Requirement: Periodic Steam poll
The system SHALL poll the Steam Web API on a periodic background schedule using
WorkManager with a minimum interval of 15 minutes, requiring network connectivity,
and SHALL reschedule itself across app restarts and device reboots.

#### Scenario: Scheduled poll succeeds
- **WHEN** the periodic worker runs with network available and valid credentials
- **THEN** it fetches `GetOwnedGames` (with app info and played free games) and `GetSteamLevel`, and persists the results to the local database

#### Scenario: No network
- **WHEN** the worker runs without connectivity
- **THEN** the run is deferred by WorkManager and the last synced data remains intact

#### Scenario: Manual sync
- **WHEN** the user triggers "Sync now"
- **THEN** a one-time expedited poll is enqueued and executes independently of the periodic schedule

### Requirement: Bounded inline sync work
A periodic or manual sync SHALL perform only a bounded amount of network work inline, proportional
to recent play activity rather than to library size, so that sync duration does not grow with the
number of games owned and cannot approach the platform's background execution limit.

#### Scenario: Sync duration independent of library size
- **WHEN** a sync runs for a player with a large library and little recent play activity
- **THEN** the number of requests it issues is proportional to recently played games, not to the
  library

#### Scenario: Library-scale work is deferred
- **WHEN** work covering the whole library is due
- **THEN** it is performed by a separate deferred pass rather than inline in the sync

#### Scenario: Manual sync stays responsive
- **WHEN** the player triggers "Sync now"
- **THEN** it completes without waiting for library-scale work

#### Scenario: A library with no stored derived data does not force a sweep
- **WHEN** a sync runs against a library for which no per-game achievement data has been stored yet,
  as on a first install or after a restore from backup
- **THEN** it still issues only a bounded number of requests, and the uncovered games are left to
  subsequent syncs and to the deferred pass rather than fetched in one inline sweep

### Requirement: Session synthesis by playtime diffing
The system SHALL synthesize play sessions by comparing each game's `playtime_forever`
against the previously stored value, since the Steam Web API does not expose session
or "currently playing" data.

#### Scenario: Playtime increases
- **WHEN** a game's `playtime_forever` is greater than its stored value
- **THEN** an open session for that game is created if none exists, extended by the delta minutes, and its last-increase timestamp updated

#### Scenario: Playtime unchanged
- **WHEN** a game with an open session shows no increase on the next poll
- **THEN** the session is closed with its end time set to the last-increase timestamp

#### Scenario: Playtime decreases
- **WHEN** a game's `playtime_forever` is less than its stored value (e.g. family sharing or refund)
- **THEN** no session is emitted and the decrease does not produce negative playtime

### Requirement: Play deltas available to dependent work
The sync SHALL make the per-game playtime deltas it computes available to work that depends on
knowing which games were played, so that information is derived once per run rather than
rediscovered by refetching.

#### Scenario: Deltas passed to achievement refresh
- **WHEN** a sync computes which games' playtime increased
- **THEN** that set is used to select which games' achievements to refresh, without additional
  requests to determine it

#### Scenario: Baseline sync yields no deltas
- **WHEN** the sync is the first one and establishes a baseline
- **THEN** no playtime deltas are reported, and no achievement refresh is triggered by play evidence
  in that run

### Requirement: Prior session state read in bulk
The sync SHALL read the prior open-session state it needs for playtime diffing in a bounded number
of database queries rather than one query per owned game.

#### Scenario: Reconstructing diff state
- **WHEN** a sync reconstructs prior session state before writing new playtime
- **THEN** the open sessions are retrieved in bulk, and the number of queries does not grow with the
  size of the library

#### Scenario: Diff results unchanged
- **WHEN** prior session state is read in bulk instead of per game
- **THEN** the synthesized sessions are identical to those produced by the per-game reads

### Requirement: First-sync baselining
The system SHALL treat the first successful poll as a baseline, recording current
playtime totals without creating any historical sessions.

#### Scenario: Initial install poll
- **WHEN** the first successful poll completes and no prior playtime is stored
- **THEN** each game's `playtime_forever` is stored as the baseline and zero sessions are created

#### Scenario: Deltas after baseline
- **WHEN** subsequent polls observe playtime increases beyond the baseline
- **THEN** only those post-baseline deltas are turned into sessions

### Requirement: Persist player identity on sync
The sync SHALL persist the player's Steam persona name and avatar URL alongside the existing
profile aggregates, so identity is available to the UI without a network call.

#### Scenario: Identity captured during sync
- **WHEN** a sync completes successfully
- **THEN** the player's current persona name and avatar URL are stored locally

#### Scenario: Identity refreshed on change
- **WHEN** a later sync observes a different persona name or avatar
- **THEN** the stored values are updated to the newer ones

#### Scenario: Identity unavailable
- **WHEN** the player summary cannot be retrieved or exposes no identity fields
- **THEN** any previously stored identity is left intact and the sync does not fail

### Requirement: Sync failure surfacing
The system SHALL detect and surface sync failures without discarding the last good
data.

#### Scenario: Private profile or empty response
- **WHEN** a poll returns no games or an authorization/privacy error
- **THEN** the app retains the last synced data and exposes a recoverable error state indicating the profile may be private

### Requirement: Concurrent polls cannot double-count
A playtime increase SHALL be recorded exactly once no matter how many polls observe it. The
system SHALL guarantee this by deriving each poll's committed delta from baselines read within
the same transaction that commits it, so a poll committing after another has already advanced
a baseline records nothing further. This guarantee SHALL NOT depend on scheduling behaviour,
work-request identity, or the two polls running in the same process.

Additionally, a manual request made while a poll is already running SHOULD be absorbed rather
than starting redundant remote work, and the interface SHALL reflect that rather than
appearing to do nothing.

#### Scenario: Manual request during a running poll
- **WHEN** the user requests a sync while a scheduled poll is already running
- **THEN** the observed playtime increase is recorded exactly once

#### Scenario: Two polls commit the same observed increase
- **WHEN** two polls both observe the same playtime increase and both reach their commit
- **THEN** the second commit derives its delta from the already-advanced baseline and records
  no additional session and no additional minutes

#### Scenario: Manual request while idle
- **WHEN** the user requests a sync and no poll is running
- **THEN** a poll begins promptly

#### Scenario: Interface reflects an absorbed request
- **WHEN** a manual request is absorbed because a poll is already in flight
- **THEN** the interface indicates that a sync is in progress rather than leaving the
  request without visible effect

#### Scenario: Daily totals are not double-credited
- **WHEN** two poll requests overlap in time for the same playtime increase
- **THEN** the day's recorded minutes increase by that increase once, not twice

### Requirement: A poll's raw persistence is atomic
The raw data a poll produces — synthesized sessions, per-game playtime baselines, daily
progress, and player profile fields — SHALL be committed as one unit that either applies
completely or not at all. A playtime baseline SHALL NOT be advanced unless the progress that
advance represents is committed with it.

Derived gamification values are written separately, immediately afterwards, through the
existing recoverable protocol that spans the database and settings storage. They are excluded
from this unit because that protocol cannot execute inside a database transaction, and because
derived values can be regenerated from committed raw data whereas raw data cannot be
regenerated from anything.

#### Scenario: Interruption during persistence
- **WHEN** a poll's persistence is interrupted partway through
- **THEN** no part of it has been applied, and the stored baseline still reflects the
  state before the poll, so the same increase is observed again on the next poll

#### Scenario: Baseline and credited progress move together
- **WHEN** a poll advances a game's playtime baseline
- **THEN** the daily progress crediting that advance is committed in the same unit, so
  no observed minutes can be stranded behind a moved baseline

#### Scenario: Network work precedes persistence
- **WHEN** a poll needs remote data to compute what it will persist
- **THEN** all such data is fetched before persistence begins, so no remote call occurs
  partway through a commit

#### Scenario: Failure preserves last-good data
- **WHEN** a poll fails at any point
- **THEN** previously stored data is unchanged, and the failure is surfaced rather than
  partially applied

#### Scenario: Interruption between raw and derived writes
- **WHEN** a poll commits its raw data and is interrupted before derived values are written
- **THEN** the raw data remains committed, the incomplete derived write is detected on the next
  attempt, and derived values are regenerated from the committed raw data

### Requirement: The sync writes only Steam-owned fields
When persisting a poll, the system SHALL update only those per-game fields for which
Steam is the authority — name, icon, total and recent playtime, the diff baseline, and
the sync timestamp. Fields the app owns — focus tagging, target minutes, and imported
history offsets — SHALL NOT be written by a poll, so that a concurrent user action or
import cannot be reverted by it.

#### Scenario: Focus toggled during a poll
- **WHEN** the user changes a game's focus flag while a poll is in progress
- **THEN** that change survives the poll's persistence

#### Scenario: History import during a poll
- **WHEN** an imported history offset is written for a game while a poll is in progress
- **THEN** that offset survives the poll's persistence

#### Scenario: A newly owned game
- **WHEN** a poll observes a game not previously stored
- **THEN** the game is created with Steam-owned fields populated and app-owned fields at
  their documented defaults

### Requirement: Derived values record and verify the configuration that produced them
Rule configuration SHALL carry a version that changes whenever the configuration changes, and
SHALL be read together with that version. Before derived values are written, the system SHALL
verify that the version is still current and SHALL refuse the write if it is not. Stored
derived values SHALL record the version that produced them, so persisted rules and persisted
derived state can be compared rather than assumed to agree.

Because rule configuration and derived values are held in separate stores that cannot commit
together, this requirement is satisfied by detecting and refusing a superseded write, not by
making the two writes atomic.

#### Scenario: Rules changed during a poll
- **WHEN** the user changes rule configuration after a poll has computed derived values but
  before that poll writes them
- **THEN** the poll does not write those derived values, and a recomputation under the current
  configuration follows

#### Scenario: Raw data survives a refused derived write
- **WHEN** a derived write is refused because the configuration changed
- **THEN** the poll's observed sessions, playtime baselines, and daily progress are still
  committed, because that data is unrecoverable and does not depend on configuration

#### Scenario: Version is recorded with the values
- **WHEN** derived values are written
- **THEN** the configuration version that produced them is stored with them

#### Scenario: Disagreement is detectable
- **WHEN** stored derived values and the current configuration are compared
- **THEN** a mismatch is identifiable from the stored version rather than being invisible

#### Scenario: Configuration unchanged
- **WHEN** the configuration is unchanged between computation and writing
- **THEN** the derived values are written and stamped with that version

### Requirement: Profile fields are written by their owning domain only
Each writer of the player profile SHALL update only the fields it owns — sync status,
Steam identity, gamification aggregates, or history-import state — rather than replacing
the whole record, so that concurrent writers in different domains cannot overwrite each
other's fields.

#### Scenario: Concurrent writes in different domains
- **WHEN** one operation updates gamification aggregates and another updates sync status
- **THEN** both updates are present afterwards

#### Scenario: Recording a sync failure
- **WHEN** a poll fails and records the failure on the profile
- **THEN** only the failure-reporting fields change, leaving identity and aggregates
  untouched
### Requirement: Playtime is attributed to a session's start date
Observed playtime SHALL be credited to the local calendar date on which its session began,
not to the date of the poll that observed it. A single poll SHALL be able to credit more
than one date when the sessions it observed began on different dates. A session's minutes
SHALL NOT be divided across dates.

#### Scenario: Session crossing midnight
- **WHEN** a session begins before local midnight and continues after it
- **THEN** all of its minutes are credited to the date on which it began

#### Scenario: Open session extended past midnight
- **WHEN** an already-open session accumulates further minutes on a poll occurring on a
  later date
- **THEN** those minutes are credited to the date the session began, not the date of the
  poll

#### Scenario: One poll spanning two dates
- **WHEN** a single poll observes minutes for one session that began yesterday and another
  that began today
- **THEN** both dates receive their respective minutes

#### Scenario: Crediting a past date reopens its evaluation
- **WHEN** minutes are credited to a date whose quest was previously evaluated
- **THEN** that date's quest status is re-evaluated, and a change from unmet to met is
  persisted

#### Scenario: Attribution does not depend on poll timing
- **WHEN** the same play activity is observed by polls at different times
- **THEN** the date credited is the same in every case

