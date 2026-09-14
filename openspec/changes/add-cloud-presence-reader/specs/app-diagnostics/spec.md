## ADDED Requirements

### Requirement: Cloud read records

The system SHALL record the outcome of each cloud presence read, including which condition
produced it, so a feature that has silently stopped working is observable after the fact.

Nothing acts on these reads in this phase, so a read that has been failing for weeks has no
user-visible symptom. The record is the only signal.

#### Scenario: Successful read

- **WHEN** a cloud read succeeds
- **THEN** a record identifies when it ran, the window it covered, and how many observations it
  returned

#### Scenario: Failed read

- **WHEN** a cloud read fails
- **THEN** a record distinguishes the cause — unreachable, rejected credential, account mismatch,
  or unusable response — since these are indistinguishable in the resulting state

#### Scenario: Trigger identified

- **WHEN** a cloud read is recorded
- **THEN** what triggered it is identified, so a read that never ran is distinguishable from one
  that ran and returned nothing

#### Scenario: Records are bounded

- **WHEN** cloud read records accumulate
- **THEN** they are retained under the same bounded retention as other diagnostic records

### Requirement: Cloud timeline is presented against the local ledger

The diagnostics surface SHALL present the reconstructed cloud timeline for a window alongside what
the local session ledger holds for that same window, so the two accounts can be compared directly
rather than inferred from separate screens.

Each presented interval SHALL state its coverage, and an interval of unknown coverage SHALL be
distinguishable at a glance from one observed continuously. An interval carrying the raw
interior-gap pair SHALL state that span alongside its tail coverage, so a fresh tail with an
interior gap is not presented as continuous.

This comparison is the evidence a later phase's design depends on. Presenting only the cloud
timeline would show what the cloud believes without showing whether it disagrees with anything,
which is the entire question.

#### Scenario: Both accounts shown for one window

- **WHEN** the surface is shown for a window a cloud read has covered
- **THEN** it presents the reconstructed cloud intervals and the locally recorded sessions for that
  same window

#### Scenario: Disagreement is legible

- **WHEN** a locally recorded session's boundaries differ from the cloud intervals covering the
  same play
- **THEN** the difference is visible without the reader computing it by hand

#### Scenario: Coverage is stated per interval

- **WHEN** intervals are presented
- **THEN** each states whether it was observed continuously, observed only until a stated time, or
  is of unknown coverage
- **AND** each carrying an interior-gap pair states that span, so a fresh tail with an interior
  gap is not presented as continuous

#### Scenario: Attribution disagreement is visible by date

- **WHEN** the cloud places play on a date the local ledger credits to a different date
- **THEN** the surface shows both attributions rather than only the local one

#### Scenario: Nothing is offered to apply

- **WHEN** the surface presents a disagreement
- **THEN** it offers no action to reconcile, correct, or import it, because this phase writes
  nothing derived

#### Scenario: Hidden games absent

- **WHEN** the reconstruction covers a game that is hidden
- **THEN** neither the cloud timeline nor the comparison names or depicts it
