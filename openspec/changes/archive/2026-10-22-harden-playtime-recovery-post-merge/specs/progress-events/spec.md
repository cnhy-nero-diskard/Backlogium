## ADDED Requirements

### Requirement: Recovery finalization preserves earned eligibility across overlapping observations
When periodic or targeted observations overlap recovery finalization, a corrective recompute SHALL NOT consume, reseed away or misclassify another operation's fresh eligible play or achievement inputs as historical correction. Corrective inputs SHALL remain silent and every fresh eligible threshold crossing SHALL retain its ordinary exactly-once delivery under the existing event, initialization and acknowledgement policies. The same guarantees SHALL hold after interruption between raw commit, corrective persistence, earned persistence and durable completion, including an acknowledgement before retry. Rule configuration and evaluation-date changes SHALL NOT silently reclassify the pending operations' captured earned/corrective inputs. Quantity and derived-state authorship SHALL remain unchanged.

#### Scenario: Periodic correction overlaps fresh targeted play
- **WHEN** periodic recovery commits historical correction and a targeted observation obtains fresh current-date earned quantity before the recovery's derived finalization finishes
- **THEN** correction produces no event and the targeted quantity retains exactly the event eligibility it would have after serial correction followed by fresh play

#### Scenario: Targeted recovery overlaps a periodic achievement
- **WHEN** targeted recovery and a periodic observation with newly earned achievement credit overlap before finalization completes
- **THEN** the correction cannot absorb the achievement's eligible progression transition and the transition is delivered at most once

#### Scenario: Two mixed operations overlap
- **WHEN** two observations each contain corrective and fresh eligible inputs and their raw and derived stages overlap
- **THEN** each fresh input retains earned classification, historical correction remains silent, and aggregate event threshold crossings follow the existing collapse and delivery policies without duplication

#### Scenario: Restart after a raw commit
- **WHEN** execution stops after one operation's raw commit and resumes while another observation is ready to commit
- **THEN** pending finalization and the new observation preserve the same quantity and earned-event eligibility as a valid uninterrupted ordering

#### Scenario: Acknowledgement precedes a stage retry
- **WHEN** earned persistence succeeds, execution stops before its durable stage-completion mark, and the resulting delivery is acknowledged before retry
- **THEN** retry neither resurrects that delivery nor consumes any independent newly earned event

#### Scenario: Rules or calendar change during interrupted finalization
- **WHEN** rule configuration or the current date changes between an operation's raw commit and resumed finalization
- **THEN** its retained earned/corrective inputs are not silently rebased into the wrong provenance and later observations retain their own ordinary event eligibility

#### Scenario: An older owed event coexists with correction
- **WHEN** recovery finalization overlaps fresh play while a prior earned delivery remains unacknowledged
- **THEN** the prior delivery remains owed, correction creates no delivery, and fresh eligible progress is not suppressed
