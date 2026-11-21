## MODIFIED Requirements

### Requirement: Session synthesis by playtime diffing
The system SHALL derive owned-game credited play by comparing each game's cumulative Steam minutes with its previously committed quantity baseline. This SHALL remain the only quantity detector for games with Steam-reported playtime; games without those totals SHALL retain their presence-derived mechanism.

The positive diff SHALL authorize exactly that many minutes, represented as timestamped sessions when usable timing exists and as unknown-time allocations when it does not. Validated timing evidence SHALL place a bounded share through the playtime-recovery rules without changing quantity; unmatched minutes SHALL use local fallback. Ordinary short-gap monitoring with a usable estimate SHALL retain its open/extend/close session behavior. A stale open row alone SHALL NOT establish usable timing across a missed interval.

#### Scenario: Playtime increases
- **WHEN** a game's cumulative total exceeds its committed quantity baseline
- **THEN** the increase is credited exactly once through sessions, unknown-time allocations, or their combination
- **AND** ordinary usable short-gap timing still opens or extends the game's session

#### Scenario: Playtime unchanged
- **WHEN** a game with an open session shows no increase on the next poll
- **THEN** the session is closed with its end time set to the last-increase timestamp

#### Scenario: Playtime decreases
- **WHEN** a game's `playtime_forever` is less than its stored value (e.g. family sharing or refund)
- **THEN** no session is emitted and the decrease does not produce negative playtime

#### Scenario: An increase placed against observed presence
- **WHEN** a playtime increase has validated timing evidence for part or all of its observation window
- **THEN** evidence-informed placement receives only its authorized bounded share and unmatched minutes use recovery fallback
- **AND** their combined credited minutes equal the observed increase

#### Scenario: No presence record available
- **WHEN** an increase has no usable timing evidence
- **THEN** the playtime-recovery fallback credits unknown-time activity without fabricated session boundaries
- **AND** cloud configuration is not required

#### Scenario: The record cannot change the total
- **WHEN** usable intervals span more or less time than the diffed increase
- **THEN** timestamped and unknown-time credited minutes together equal the diffed increase exactly

### Requirement: Playtime is attributed to a session's start date
Timestamped session minutes SHALL be credited to the local date on which that session began, rather than the poll date, and a timestamped session SHALL remain atomic across midnight. Multiple timestamped sessions SHALL be attributed independently by start date.

Unknown-time play SHALL instead be credited to its explicit frozen allocation dates under playtime-recovery. An unknown-time allocation SHALL NOT acquire a synthetic session-start timestamp to satisfy this rule. One increase SHALL be able to credit multiple dates through independent sessions and/or day allocations. Crediting or moving minutes on a previously evaluated date SHALL re-evaluate that date's quest, including a change from met to unmet after placement correction.

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

#### Scenario: One increase credited to several dates
- **WHEN** one observed increase is written as several sessions that began on different dates
- **THEN** each date receives the minutes of the sessions that began on it
- **AND** no session's minutes are divided between them

#### Scenario: Crediting a past date reopens its evaluation
- **WHEN** minutes are credited to a date whose quest was previously evaluated
- **THEN** that date's quest status is re-evaluated, and a change from unmet to met is
  persisted

#### Scenario: Attribution does not depend on poll timing
- **WHEN** the same credited activity has the same usable timing evidence or frozen allocation dates
- **THEN** every surface uses those dates independently of when a later poll or render occurs

#### Scenario: Unknown-time allocation date
- **WHEN** recovered play is allocated to Wednesday without session timing
- **THEN** Wednesday receives that credit without creating a session start

### Requirement: First-sync baselining
The system SHALL treat the first successful poll as a baseline, recording current
playtime totals without creating any historical sessions or unknown-time allocations. A baseline poll SHALL NOT record an
arrival time for any game it observes, so that the games a player already owned are never
presented as newly acquired.

#### Scenario: Initial install poll
- **WHEN** the first successful poll completes and no prior playtime is stored
- **THEN** each game's `playtime_forever` is stored as the baseline and zero sessions or unknown-time allocations are created

#### Scenario: Deltas after baseline
- **WHEN** subsequent polls observe playtime increases beyond the baseline
- **THEN** only those post-baseline deltas are credited through sessions or unknown-time allocations

#### Scenario: Baseline records no arrivals
- **WHEN** the baseline poll stores a library of any size
- **THEN** no game is given an arrival time and no acquisition is announced

#### Scenario: Baseline still records last-played times
- **WHEN** the baseline poll observes games with last-played times reported by Steam
- **THEN** those times are stored, since they describe existing history rather than a change

### Requirement: A poll's raw persistence is atomic
The raw data a poll produces — synthesized sessions, recovery snapshots, funded contributions, unknown-time allocations, per-game playtime baselines, daily
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

#### Scenario: Recovery baseline and allocation commit together
- **WHEN** a poll advances a recovery baseline and authorizes unknown-time minutes
- **THEN** the snapshot, contribution, allocation and raw daily credit commit as one unit
- **AND** derived-state materialization remains recoverable through the existing protocol

### Requirement: Targeted fetches commit through the ordinary poll path
A targeted playtime fetch SHALL apply its observation through the same credited-activity synthesis and
persistence path as a periodic poll, deriving its committed delta from baselines read within the
committing transaction. It SHALL NOT synthesize sessions or allocations, credit daily progress, or author derived
values by any separate route.

#### Scenario: Same increase observed by both sources
- **WHEN** a targeted fetch and a periodic poll both observe the same playtime increase
- **THEN** the increase is recorded exactly once, and the second to commit records no additional
  session and no additional minutes

#### Scenario: Records are indistinguishable by source
- **WHEN** a targeted fetch and a periodic poll supply equivalent quantity and timing evidence
- **THEN** their credited activity, quantity baseline and daily progress are equivalent
- **AND** targeted evidence of a known play instant is retained when a later fetch observes the total

#### Scenario: The observation carries the time the play ended
- **WHEN** a targeted fetch commits an observation
- **THEN** it supplies the session end that triggered its schedule as the time the play occurred,
  rather than the time the attempt ran

#### Scenario: A later attempt does not report a later play
- **WHEN** the increase is observed by a late attempt of the schedule rather than the first
- **THEN** the time the play occurred is the same session end, so the record does not drift later
  with the attempt that happened to see it

#### Scenario: Steam-owned fields are not locally authored
- **WHEN** a targeted fetch commits an observation and its response carries no last-played time
- **THEN** it leaves the stored last-played time unchanged for the next periodic poll to set,
  rather than writing a locally-derived value into a Steam-owned field

#### Scenario: Derived values keep one author
- **WHEN** a targeted fetch commits an observation
- **THEN** derived values are written by the existing on-device path, and no second derivation is
  performed

#### Scenario: Playtime decrease
- **WHEN** a targeted fetch observes playtime lower than the stored baseline
- **THEN** no session is emitted and no negative playtime is produced

#### Scenario: Concurrency with a running poll
- **WHEN** a targeted fetch runs while a periodic poll is already in flight
- **THEN** exactly-once crediting still holds, and the targeted fetch neither fails nor is skipped
  on account of the overlap
