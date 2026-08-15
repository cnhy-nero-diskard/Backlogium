## MODIFIED Requirements

### Requirement: The sync writes only Steam-owned fields
When persisting a poll, the system SHALL update only those per-game fields for which
Steam is the authority — name, icon, total and recent playtime, the last-played time, the diff
baseline, and the sync timestamp. Fields the app owns — focus tagging, target minutes, and imported
history offsets — SHALL NOT be written by a poll, so that a concurrent user action or
import cannot be reverted by it.

The time a game first appeared in the library is written by a poll exactly once, when that poll
observes a game not previously stored and is not establishing the library baseline. A poll SHALL
NOT write it for a game that already has one, and SHALL NOT write it for any game during a baseline
poll.

A poll that observes a play increase SHALL evaluate, before overwriting the game's stored last-played
time, whether the play it observed followed a dormant period, and SHALL record a return where it
did. This evaluation SHALL happen within the poll because the value it depends on is destroyed by the
poll's own update.

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

#### Scenario: Arrival time stamped once
- **WHEN** a non-baseline poll observes a game not previously stored
- **THEN** its arrival time is set to the time of that poll and is never changed by a later poll

#### Scenario: Last-played time is Steam-owned
- **WHEN** a poll observes an owned game
- **THEN** its stored last-played time is updated from Steam's reported value

#### Scenario: Last-played time absent from the payload
- **WHEN** Steam's response omits a last-played time for a game
- **THEN** that game's stored last-played time is left unknown and the poll does not fail

#### Scenario: Return evaluated before the overwrite
- **WHEN** a poll observes a play increase for a game
- **THEN** it evaluates dormancy against the previously stored last-played time before replacing it

#### Scenario: Return recorded in the same commit
- **WHEN** a poll records a return for a game
- **THEN** the return is committed in the same unit as the playtime baseline and last-played time it
  was derived from, so no combination of the three can be observed partially applied

### Requirement: First-sync baselining
The system SHALL treat the first successful poll as a baseline, recording current
playtime totals without creating any historical sessions. A baseline poll SHALL NOT record an
arrival time for any game it observes, so that the games a player already owned are never
presented as newly acquired.

#### Scenario: Initial install poll
- **WHEN** the first successful poll completes and no prior playtime is stored
- **THEN** each game's `playtime_forever` is stored as the baseline and zero sessions are created

#### Scenario: Deltas after baseline
- **WHEN** subsequent polls observe playtime increases beyond the baseline
- **THEN** only those post-baseline deltas are turned into sessions

#### Scenario: Baseline records no arrivals
- **WHEN** the baseline poll stores a library of any size
- **THEN** no game is given an arrival time and no acquisition is announced

#### Scenario: Baseline still records last-played times
- **WHEN** the baseline poll observes games with last-played times reported by Steam
- **THEN** those times are stored, since they describe existing history rather than a change
