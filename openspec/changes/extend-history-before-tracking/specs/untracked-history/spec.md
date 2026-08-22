## ADDED Requirements

### Requirement: Dated achievement unlocks produce a day
The system SHALL treat a local date on which one or more achievements were unlocked as a day of
history, whether or not that date has tracked sessions or a stored progress row. An unlock SHALL NOT
be discarded because its date holds no other recorded activity.

#### Scenario: Unlock on a day with no session
- **WHEN** an achievement's unlock date has no tracked session and no stored progress row
- **THEN** that date appears as a day of history carrying that unlock

#### Scenario: Unlock inside the tracked period
- **WHEN** an achievement unlocks on a date within the period the app has been tracking, on which no
  session was recorded
- **THEN** that date appears as a day of history rather than being omitted

#### Scenario: Unlock on a day that already exists
- **WHEN** an achievement's unlock date already has tracked sessions
- **THEN** the unlock appears on that existing day and no duplicate day is produced

#### Scenario: Unlock with no recorded time
- **WHEN** an unlocked achievement has no unlock time recorded
- **THEN** it produces no day, and it is accounted for as undated rather than dropped without trace

### Requirement: History extends to the earliest dated evidence
The system SHALL allow history to be viewed back to the earliest date for which dated evidence
exists, rather than stopping at the first tracked session. Where no further dated evidence exists
earlier than the period already shown, the system SHALL NOT offer to load earlier days.

#### Scenario: Reaching before tracking began
- **WHEN** dated unlocks exist earlier than the first tracked session and the user loads earlier days
- **THEN** those earlier dates become reachable

#### Scenario: The floor is the earliest evidence
- **WHEN** the earliest dated evidence has been reached
- **THEN** no further earlier days are offered

#### Scenario: No evidence before tracking
- **WHEN** the earliest dated unlock is not earlier than the first tracked session
- **THEN** history behaves as it does today, with no additional reach offered

### Requirement: A day before tracking is a distinct kind of day
The system SHALL distinguish a day whose content is dated unlocks alone from a day with tracked
sessions, as a property of the day rather than only in how it is drawn, so that every consumer
answers for the distinction.

#### Scenario: Kind is carried on the day
- **WHEN** a day is produced from dated unlocks alone
- **THEN** it is identifiable as such without inspecting whether its session list is empty

#### Scenario: A tracked day is unchanged
- **WHEN** a day has tracked sessions
- **THEN** it carries the same content and behaviour it carries today

#### Scenario: A day with both
- **WHEN** a date within the tracked period has both sessions and unlocks
- **THEN** it is a tracked day carrying its unlocks, not a day of the pre-tracking kind

### Requirement: Unknown minutes are never presented as zero
For a day whose played minutes are not recoverable, the system SHALL present those minutes as
unknown and SHALL NOT present them as zero, as an empty total, or as an absence of play.

#### Scenario: Minutes unknown
- **WHEN** a pre-tracking day is presented
- **THEN** its played time is conveyed as unknown

#### Scenario: Distinguishable from a genuinely idle day
- **WHEN** a tracked day with no play and a pre-tracking day are both presented
- **THEN** the two are distinguishable, and the pre-tracking day is not shown as having played
  nothing

#### Scenario: Unknown minutes contribute nothing to a total
- **WHEN** any total, average, or chart is derived across days
- **THEN** a day with unknown minutes contributes no value to it and is not counted as zero

### Requirement: Pre-tracking days are presentational only
A day produced from dated unlocks alone SHALL NOT cause a per-day progress row to be written, SHALL
NOT be evaluated for a daily quest, and SHALL NOT enter the day sequence from which streaks are
computed. It SHALL present no quest state, rather than presenting an unmet one.

#### Scenario: No progress row written
- **WHEN** pre-tracking days are produced
- **THEN** no per-day progress row is created, updated, or deleted for them

#### Scenario: Streaks unaffected
- **WHEN** pre-tracking days exist
- **THEN** the current streak and the longest streak are identical to what they were before those
  days became visible

#### Scenario: Quest state absent, not unmet
- **WHEN** a pre-tracking day is presented
- **THEN** it shows no quest state, since the app cannot know whether that day would have met a
  quest that did not yet exist

#### Scenario: Current level unaffected
- **WHEN** pre-tracking days are produced
- **THEN** the player's stored total XP, level, and progress within the level are unchanged

### Requirement: Pre-tracking play is not estimated
The system SHALL NOT estimate, apportion, or infer played minutes for any date, from lifetime
playtime totals, from achievement counts, from HowLongToBeat lengths, or from a rate observed on
tracked days.

#### Scenario: Lifetime totals are not divided across days
- **WHEN** a game has substantial lifetime playtime and dated unlocks before tracking began
- **THEN** no portion of that playtime is attributed to any of those dates

#### Scenario: No rate is extrapolated backwards
- **WHEN** tracked days establish a typical daily play amount
- **THEN** that amount is not applied to any pre-tracking day

#### Scenario: Unknown remains unknown
- **WHEN** any surface would benefit from a number for a pre-tracking day's minutes
- **THEN** the absence is carried through rather than filled
