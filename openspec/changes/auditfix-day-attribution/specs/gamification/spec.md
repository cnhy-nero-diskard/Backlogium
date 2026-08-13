# gamification

## ADDED Requirements

### Requirement: The supplied day sequence is contiguous
Because the engine folds streaks by list order and performs no I/O, the caller SHALL supply
a contiguous sequence of calendar days, synthesizing an unmet day for any date in the
evaluated span that has no stored progress. Dates with no stored progress SHALL NOT be
omitted from the sequence, so that list order and calendar order agree and a gap cannot be
read as adjacency.

#### Scenario: Gap between stored days
- **WHEN** progress is stored for a Monday and a Thursday with nothing stored between them
- **THEN** the sequence supplied to streak computation includes unmet entries for the
  intervening Tuesday and Wednesday, and the resulting current streak counts only the met
  days since the last break

#### Scenario: Synthesized days are not persisted
- **WHEN** unmet days are synthesized to fill a gap
- **THEN** no stored progress record is created for them, and the count of stored records is
  unchanged

#### Scenario: Span of the sequence
- **WHEN** the sequence is constructed
- **THEN** it spans from the earliest stored progress date through the current date, and
  contains no dates earlier than the earliest stored record

#### Scenario: Grace applies to synthesized gaps
- **WHEN** a grace allowance is configured and a gap is filled with synthesized unmet days
- **THEN** those days are eligible for grace on the same terms as any other unmet day

#### Scenario: Engine contract is unchanged
- **WHEN** the engine is given a sequence
- **THEN** it folds by list order as before, remaining free of I/O and of any notion of the
  calendar
