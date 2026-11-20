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

#### Scenario: Partial presence cannot absorb a whole increase
- **WHEN** a 105-minute increase has only 35 whole confirmed minutes of non-overlapping usable timing and no previously funded share
- **THEN** timed placement receives 35 minutes and unknown-time recovery receives 70
- **AND** no synthetic session is created to hold the residual

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

#### Scenario: Correction re-evaluates the date losing minutes
- **WHEN** a placement-only correction moves qualifying minutes away from a previously met date
- **THEN** that date's daily total and quest are re-evaluated, including persisting a met-to-unmet change
- **AND** the same correction re-evaluates the receiving dates without changing the game's credited quantity
