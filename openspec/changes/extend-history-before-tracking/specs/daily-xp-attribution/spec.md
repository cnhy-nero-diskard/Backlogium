## ADDED Requirements

### Requirement: A day's XP is marginal, not a sum of its minutes
The system SHALL define the XP attributed to a day as the amount by which that day's tracked play
and achievement unlocks increased the player's total, evaluated against the cumulative state
preceding that day. It SHALL NOT compute a day's XP by applying a flat rate to that day's minutes.

#### Scenario: The same minutes are worth different amounts
- **WHEN** equal tracked minutes fall on two days for a game whose cumulative playtime differs
  greatly between them
- **THEN** the two days are attributed different XP, reflecting the taper

#### Scenario: Play beyond the taper's ceiling
- **WHEN** a day's play falls entirely on a game already at or beyond the point where further
  minutes earn nothing
- **THEN** that day is attributed no playtime XP for that game

#### Scenario: Achievements contribute their own XP
- **WHEN** achievements unlock on a day
- **THEN** their XP, computed from each achievement's rarity snapshot, is attributed to the local
  date of the unlock

#### Scenario: Order within a day does not matter
- **WHEN** a day's sessions and unlocks are evaluated in any order
- **THEN** the day's attributed XP is the same

### Requirement: Daily XP is computed by the existing engine
The system SHALL compute daily XP by calling the same gamification entry points that produce the
player's total, supplying the same inputs — cumulative minutes including any frozen imported
backfill, HowLongToBeat completionist lengths, and each unlocked achievement's rarity snapshot — and
the same rule configuration. It SHALL NOT define a second XP formula.

#### Scenario: One source of truth
- **WHEN** daily XP is computed
- **THEN** it is produced by the same entry points that produce the player's total XP

#### Scenario: Snapshot rarity, not live rarity
- **WHEN** an achievement's current global percentage differs from its rarity snapshot
- **THEN** the day's attributed XP uses the snapshot

#### Scenario: An unlocked achievement without a snapshot
- **WHEN** an achievement is unlocked but carries no rarity snapshot
- **THEN** it contributes no XP to any day, consistent with its contribution to the total

#### Scenario: Backfill participates in the taper
- **WHEN** a game carries frozen imported backfill minutes
- **THEN** each day's marginal XP for that game is evaluated on top of that offset, so imported
  history reduces what later tracked minutes are worth exactly as it does in the total

### Requirement: The daily series reconciles to the total
The system SHALL ensure that the sum of every day's attributed XP, plus an undated remainder, equals
the player's stored total XP. The undated remainder SHALL consist of exactly two sources: the XP
attributable to imported historical playtime, which carries no date, and the XP of unlocked
achievements for which Steam reported no unlock time.

#### Scenario: Series plus remainder equals the total
- **WHEN** every attributed day and the undated remainder are summed
- **THEN** the result equals the player's stored total XP

#### Scenario: Imported history is in the remainder
- **WHEN** the player has imported Steam history
- **THEN** the XP attributable to that imported playtime appears in the undated remainder and on no
  day

#### Scenario: Undated unlocks are in the remainder
- **WHEN** an unlocked achievement has no recorded unlock time
- **THEN** its XP appears in the undated remainder and on no day

#### Scenario: No imported history and every unlock dated
- **WHEN** the player has not imported history and every unlocked achievement carries an unlock time
- **THEN** the undated remainder is zero and the daily series alone equals the total

#### Scenario: Reset of an import
- **WHEN** the player resets a completed history import
- **THEN** the undated remainder falls by the amount that import contributed, and the daily series is
  unchanged

### Requirement: The undated remainder is presented, never absorbed
The system SHALL present the undated remainder where daily XP is accounted for, naming what it
consists of, and SHALL NOT attribute it to any day — including the earliest day, the day of first
sync, or the current day.

#### Scenario: Remainder shown
- **WHEN** the undated remainder is non-zero
- **THEN** it is presented alongside the daily accounting, identified as XP the app counts but cannot
  place in time

#### Scenario: Remainder not folded into a day
- **WHEN** the undated remainder is non-zero
- **THEN** no day's attributed XP includes any part of it

#### Scenario: Remainder absent
- **WHEN** the undated remainder is zero
- **THEN** nothing is presented for it, rather than a zero line

### Requirement: Daily XP is derived, never stored
The system SHALL derive daily XP from stored sessions, achievements, games, and completion lengths
at the time it is presented, and SHALL NOT persist a per-day XP value.

#### Scenario: Nothing persisted
- **WHEN** daily XP is presented
- **THEN** no per-day XP value has been written to storage

#### Scenario: A new session changes its day
- **WHEN** a sync records a session on a day whose XP has already been presented
- **THEN** that day's attributed XP reflects the new session the next time it is shown, with no
  recompute job or invalidation step

#### Scenario: Rule change re-derives every day
- **WHEN** the player edits the gamification rules
- **THEN** every day's attributed XP is re-derived under the new rules, consistently with the
  disclosed retroactive effect of a rule change

#### Scenario: Series cannot go stale
- **WHEN** any input to XP changes by any path — sync, unlock, import, reset, or rule edit
- **THEN** the presented series reflects it without a stored value needing invalidation
