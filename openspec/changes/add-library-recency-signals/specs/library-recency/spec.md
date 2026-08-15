## ADDED Requirements

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
newly played over returned to play, and returned to play over newly added.

#### Scenario: Game arrives and is not played
- **WHEN** a game arrived within the recency window and has no recorded playtime
- **THEN** its state is newly added

#### Scenario: Game played for the first time
- **WHEN** a game's first ever recorded session occurred within the recency window
- **THEN** its state is newly played

#### Scenario: Newly played fires once per game
- **WHEN** a game that has already had a first recorded session is played again
- **THEN** it does not become newly played again

#### Scenario: Dormant game played again
- **WHEN** a return from dormancy was recorded for a game within the recency window
- **THEN** its state is returned to play

#### Scenario: Game bought and played the same day
- **WHEN** a game arrived within the recency window and was played for the first time within it
- **THEN** its state is newly played, not newly added

#### Scenario: Long-owned game played for the first time
- **WHEN** a game owned since before tracking began is played for the first recorded time
- **THEN** its state is newly played, not returned to play

#### Scenario: States expire
- **WHEN** the recency window has elapsed since the event that produced a game's state
- **THEN** that game carries no state, without any write having occurred to expire it

#### Scenario: Settled game carries nothing
- **WHEN** a game has not arrived recently and has not been played recently
- **THEN** it carries no recency state

#### Scenario: Regularly played game carries nothing
- **WHEN** a game is played often, with no gap exceeding the dormancy threshold
- **THEN** it carries no recency state after its first session's window has elapsed

#### Scenario: At most one state
- **WHEN** any game's recency state is derived
- **THEN** the result is exactly one state or none, never two

### Requirement: A return from dormancy is recorded when it is observed
Because the evidence that a game was dormant is destroyed by the same observation that ends the
dormancy, the system SHALL evaluate dormancy at the moment a poll observes a game's play increase —
while both the previously known last-play time and the new one are available — and SHALL record that
a return occurred, and when. The system SHALL NOT attempt to reconstruct a dormancy gap from stored
state after the fact.

The previously known last-play time SHALL be the later of the game's most recent recorded session and
its stored last-played time as it was before that poll's update.

#### Scenario: Return recorded on observation
- **WHEN** a poll observes a play increase for a game whose previously known last-play time is older
  than the dormancy threshold
- **THEN** a return is recorded for that game at the time of that poll

#### Scenario: No return for continuous play
- **WHEN** a poll observes a play increase for a game whose previously known last-play time is within
  the dormancy threshold
- **THEN** no return is recorded and any previously recorded return is left unchanged

#### Scenario: Prior play known only from recorded sessions
- **WHEN** a returning game's most recent recorded session is later than its stored last-played time
- **THEN** the session's time is used as the previously known last-play time

#### Scenario: Prior play known only from the stored last-played time
- **WHEN** a returning game has no recorded session earlier than the current play
- **THEN** its stored last-played time as it stood before this poll is used as the previously known
  last-play time

#### Scenario: Last-played time is not overwritten before it has been used
- **WHEN** a poll updates a game's stored last-played time
- **THEN** the dormancy evaluation for that poll has already read the previous value

#### Scenario: Neither source knows anything
- **WHEN** a game has no recorded session and no stored last-played time preceding the observed play
- **THEN** no return is recorded, rather than one being recorded speculatively

#### Scenario: Derivation reads only the recorded return
- **WHEN** a game's returned-to-play state is derived
- **THEN** it depends only on whether a recorded return falls within the recency window, and not on
  any attempt to re-measure the gap

### Requirement: A baseline produces no recency states
A first sync against an untracked library SHALL leave every game it observes with no recency state
and SHALL produce no acquisition announcement, so that a library the player already owned is never
presented as newly acquired.

#### Scenario: Fresh install
- **WHEN** the first sync completes on a fresh install of a large library
- **THEN** no game carries a recency state and no acquisition is announced

#### Scenario: Upgrade of an existing library
- **WHEN** an existing library is read for the first time after recency tracking is introduced
- **THEN** no game carries a recency state

#### Scenario: First genuine acquisition after baseline
- **WHEN** a poll after the baseline observes a game the library has no record of
- **THEN** that game carries a recency state and is announced

### Requirement: A restore reproduces recency data without creating recency events
A restore SHALL NOT itself record an arrival, record a return, or produce an acquisition
announcement. Recency data carried by the backup SHALL be restored as recorded, and any state derived
from it SHALL follow from the recency windows applied to those recorded times, exactly as it would
have on the device the backup came from.

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
