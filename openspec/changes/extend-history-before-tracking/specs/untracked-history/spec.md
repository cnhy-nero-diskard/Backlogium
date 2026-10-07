## Purpose
Represent dated achievement evidence even when no corresponding playtime record exists, without manufacturing gameplay history.

## ADDED Requirements

### Requirement: Dated unlocks produce evidence-only days
History SHALL include valid dated unlocks in its day union alongside recorded sessions and daily progress. An unlock-only day SHALL have unknown playtime/Focus time and unavailable quest state. It SHALL NOT be labelled before tracking unless a separate reliable boundary proves that classification.

#### Scenario: Unlock-only date inside tracked era
- **WHEN** an unlock date has no session or progress row but lies between tracked days
- **THEN** it is shown as evidence-only, not zero playtime or an unmet quest

#### Scenario: Older dated unlock
- **WHEN** an unlock predates every stored session
- **THEN** its date remains visible as evidence without inferring an installation date

#### Scenario: Undated unlock
- **WHEN** an unlocked achievement has no usable date
- **THEN** it does not fabricate a day and its contribution remains in undated accounting

### Requirement: Paging reaches the earliest dated evidence
History SHALL preserve bounded calendar windows while permitting paging to the minimum relevant date across sessions, recorded progress, and dated unlocks. Hidden/retired exclusion policy SHALL remain consistent with the existing contributing data. An empty page SHALL NOT imply the evidence floor was reached.

#### Scenario: Progress predates sessions
- **WHEN** the oldest relevant datum is a daily-progress row
- **THEN** older paging remains available until that date is reached

#### Scenario: Unlock predates progress
- **WHEN** the oldest relevant datum is a dated unlock
- **THEN** the unlock date determines the floor

#### Scenario: Sparse history
- **WHEN** a window contains no rows but older evidence exists
- **THEN** the user can continue paging without loading an unbounded history list

### Requirement: Evidence-only days do not create gameplay facts
Showing a dated unlock SHALL NOT create sessions, distribute lifetime playtime, write progress rows, evaluate a missing quest, or change streak/Personal Pace inputs. Recorded zero and unavailable playtime SHALL remain distinct.

#### Scenario: Evidence-only day opens
- **WHEN** a player expands a day containing only unlock evidence
- **THEN** the day reveals its achievement evidence without fabricated sessions or minutes

#### Scenario: Actual zero recorded
- **WHEN** a daily-progress row records zero minutes
- **THEN** zero is presented as recorded data rather than confused with unknown evidence-only playtime
