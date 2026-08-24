# library-recency

## Purpose

Defines how the system tracks when games enter and are played in the library, derives
three mutually exclusive recency states (newly added, newly played, returned to play),
and announces newly acquired games to the player for a bounded period.

## Requirements

### Requirement: When a game entered the library is recorded
The system SHALL record, per game, when that game first appeared in the library, and SHALL
distinguish a game that arrived while the app was tracking the library from one that was already
present when tracking began. A game already present when tracking began SHALL have no arrival time
rather than a substituted one.

#### Scenario: Game arrives after tracking has begun
- **WHEN** a poll that is not a baseline observes an owned game the library has no record of
- **THEN** that game's arrival time is recorded as the time it was observed

#### Scenario: Game present at baseline
- **WHEN** the first poll establishes the library baseline
- **THEN** no game it observes is given an arrival time

#### Scenario: Existing library gains the field
- **WHEN** a library recorded before arrival times were tracked is read
- **THEN** every game in it has no arrival time, and none is treated as having arrived

#### Scenario: Arrival time is written once
- **WHEN** a game with a recorded arrival time is observed by later polls
- **THEN** its arrival time is unchanged

#### Scenario: Restore does not record arrivals
- **WHEN** a restore inserts games that are not present in the current library
- **THEN** no arrival time is recorded for them beyond what the backup itself carried

### Requirement: A game's last-played time is recorded
The system SHALL record, per game, the time Steam reports that game was last played, refreshing it
on each poll that observes it. The system SHALL distinguish a game that has never been played from
one whose last-played time is unknown.

#### Scenario: Last-played time captured
- **WHEN** a poll observes a game for which Steam reports a last-played time
- **THEN** that time is stored for the game

#### Scenario: Last-played time refreshed
- **WHEN** a later poll observes a newer last-played time for a game
- **THEN** the stored value is updated

#### Scenario: Steam reports no last-played time
- **WHEN** Steam reports no last-played time for an owned game
- **THEN** the game's last-played time is recorded as unknown, and the poll does not fail

#### Scenario: Never played is distinct from unknown
- **WHEN** a game has no recorded playtime at all
- **THEN** it is treated as never played, regardless of whether a last-played time is known
#### Scenario: Game arrives and is not played
- **WHEN** a game arrived within the recency window and has no recorded playtime
- **THEN** its state is newly added

#### Scenario: Game played for the first time
- **WHEN** a game's first ever recorded session occurred within the recency window
- **THEN** its state is newly played

#### Scenario: Newly played beats returned to play
- **WHEN** a game's first ever session occurred within the recency window and it was also played
  recently after a dormant period
- **THEN** its state is newly played, not returned to play

#### Scenario: Returned to play
- **WHEN** a game that had been dormant is played recently within the recency window, but its first
  ever session is older than that window
- **THEN** its state is returned to play

#### Scenario: Returned to play beats newly added
- **WHEN** a game arrived within the recency window and is played recently after a dormant period
  (with its first ever session being older than the recency window)
- **THEN** its state is returned to play, not newly added

#### Scenario: No state after expiry
- **WHEN** a game has no recent arrival, no recent first play, and no recent play after dormancy
- **THEN** it carries no recency state

#### Scenario: State is derived, not stored
- **WHEN** the recency state is needed for any game
- **THEN** it is derived from recorded data each time, and no stored state field is consulted

### Requirement: State derivation uses recorded times, not the observation clock
The recency window SHALL be measured from the recorded event time, not from the time the event was
observed, so that a delayed observation — whether from batching, a long-running poll, or a backed-up
sync — does not stretch or shrink the window.

#### Scenario: Early observation does not shrink the window
- **WHEN** a poll runs soon after a play occurs and records the last-played time
- **THEN** the recency window is measured from when the play happened, not from the observation time,
  so the window is not shortened

#### Scenario: Late observation does not stretch the window
- **WHEN** a poll runs long after a play occurred and records the last-played time from that earlier
  play
- **THEN** the recency window is measured from when the play happened, so the window began earlier
  and may already have expired — the delay does not grant extra time

### Requirement: Baseline does not record arrivals or announcements
When the system establishes the library baseline, it SHALL NOT record arrival times for the games it
observes and SHALL NOT present an acquisition announcement. A baseline is defined as the first
successful poll for the configured account when no stored playtime baseline exists.

#### Scenario: First poll is a baseline
- **WHEN** the first successful poll for the configured account completes
- **THEN** no game is recorded as an arrival and no acquisition announcement is presented

#### Scenario: First poll on a new account
- **WHEN** a poll completes after a confirmed account change and no stored playtime baseline exists
- **THEN** that poll is treated as a baseline, recording no arrivals and presenting no announcement

### Requirement: Empty library baselines are persisted
When the baseline poll observes zero owned games, the system SHALL record that baseline so that
subsequent polls observe deltas rather than treating the first non-empty poll as the baseline.

#### Scenario: Empty baseline
- **WHEN** the first poll observes no owned games
- **THEN** the baseline is persisted as empty

#### Scenario: Games appear after an empty baseline
- **WHEN** a later poll observes games after an empty baseline was persisted
- **THEN** those games are recorded as arrivals, since the baseline was already established

#### Scenario: Played but undated
- **WHEN** a game has recorded playtime but no known last-played time
- **THEN** it is treated as played with an unknown date, not as never played

### Requirement: Three mutually exclusive recency states
The system SHALL derive at most one recency state per game from recorded data, never storing the
state itself. The states SHALL be: **newly added** — arrived recently and not yet played; **newly
played** — its first ever recorded session happened recently; and **returned to play** — played
recently after a long period with no play. Every state SHALL expire on its own, so a game with no
recent change carries no state.

Where more than one state's conditions hold, the system SHALL resolve to exactly one, preferring
### Requirement: Recency data is durable across restarts and backups
Recency data SHALL be stored in the same database as the library it describes, SHALL survive app
restarts without re-derivation from scratch, and SHALL survive backup-and-restore so that a restored
device sees the same recency states it would have seen on the original device.

#### Scenario: App restart preserves arrival and last-played times
- **WHEN** the app is restarted
- **THEN** stored arrival times and last-played times are still present and contribute to state
  derivation

#### Scenario: Backup carries recency data
- **WHEN** a backup is exported
- **THEN** the arrival times, last-played times, and recorded returns are included

#### Scenario: Restore preserves recorded times
- **WHEN** a backup is imported
- **THEN** the arrival times, last-played times, and recorded returns are restored

#### Scenario: Restored data is interpreted on its own timeline
- **WHEN** an import completes
- **THEN** any recency state a game carries follows from the times the backup recorded, exactly as it
  would have on the device the backup came from

#### Scenario: Restore records no arrivals of its own
- **WHEN** a restore inserts games that are not present in the current library
- **THEN** no arrival time is recorded for them beyond one carried by the backup

#### Scenario: Restore records no returns of its own
- **WHEN** a restore inserts or updates games
- **THEN** no return from dormancy is recorded as a consequence of the restore

#### Scenario: Restore produces no announcement
- **WHEN** a restore inserts previously unknown games
- **THEN** no acquisition announcement is presented

#### Scenario: Old backup carries expired signals
- **WHEN** a backup older than the recency window is restored
- **THEN** no game carries a recency state, because the recorded times have already aged out

#### Scenario: Recent backup carries live signals
- **WHEN** a backup taken within the recency window is restored, carrying a game recorded as having
  arrived within that window
- **THEN** that game carries the corresponding state, since the recorded arrival is still recent

#### Scenario: Backup predating recency tracking
- **WHEN** a backup written before recency data existed is restored
- **THEN** the affected games have no recorded arrival and carry no recency state

#### Scenario: Sync after a restore is not a baseline
- **WHEN** a sync runs after a restore and observes a game neither the backup nor the library
  contained
- **THEN** that game is recorded as an arrival and announced, since prior playtime is already stored
  and the poll is therefore not a baseline

### Requirement: Newly acquired games are announced for a bounded period
When a poll observes games the library has no record of, the system SHALL announce them, naming how
many arrived and identifying at least some of them. The announcement SHALL be dismissible, SHALL
expire on its own within a day of the poll that produced it, and SHALL NOT require the app to have
been running for either to happen.

#### Scenario: Games acquired
- **WHEN** a poll observes one or more games the library has no record of
- **THEN** an announcement is presented reporting how many arrived

#### Scenario: Announcement identifies the games
- **WHEN** the announcement is presented
- **THEN** it names at least one arrived game, and reports the count of any it does not name

#### Scenario: Dismissed
- **WHEN** the user dismisses the announcement
- **THEN** it is not presented again for that set of games

#### Scenario: Expiry without dismissal
- **WHEN** a day has passed since the poll that produced the announcement
- **THEN** it is no longer presented, whether or not the app was running during that day

#### Scenario: A later acquisition supersedes an earlier one
- **WHEN** a further poll observes more previously unknown games
- **THEN** the announcement describes the newer set and is presented again even if the earlier one
  had been dismissed

#### Scenario: No acquisition, no announcement
- **WHEN** a poll observes no previously unknown games
- **THEN** no announcement is presented and any existing one is left as it was

#### Scenario: Announcement is not restored
- **WHEN** a backup is restored
- **THEN** no announcement from the backed-up device is presented

#### Scenario: Announcement does not block the surface
- **WHEN** the announcement is presented
- **THEN** the surface behind it remains usable and the announcement is not modal
newly played over returned to play, and returned to play over newly added.