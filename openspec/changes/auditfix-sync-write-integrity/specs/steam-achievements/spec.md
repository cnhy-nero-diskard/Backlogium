# steam-achievements

## ADDED Requirements

### Requirement: Refreshes of the same game are serialized
The system SHALL ensure that two achievement refreshes for the same game cannot be in
flight at once, so that a merge computed from older state cannot be committed after a
merge computed from newer state.

#### Scenario: Sync and reconciliation overlap
- **WHEN** a scheduled reconciliation and a normal sync would each refresh the same
  game's achievements
- **THEN** only one refresh runs, or they run strictly one after the other

#### Scenario: Newer observation is not overwritten
- **WHEN** two refreshes for one game complete out of the order they started in
- **THEN** the stored unlock state reflects the newer observation, not the older one

#### Scenario: Rarity snapshot invariant holds under concurrency
- **WHEN** refreshes for one game overlap in time
- **THEN** the first-unlock rarity snapshot is still written exactly once and is not
  replaced by a later observation

### Requirement: Achievements Steam stops returning are retired, not deleted
When a refresh no longer includes an achievement that is stored locally, the system SHALL
mark that achievement as no longer offered and exclude it from counts, displayed totals,
and experience, while retaining its stored row and its first-unlock rarity snapshot.
Rows SHALL NOT be deleted on absence from a single response.

#### Scenario: Achievement absent from a response
- **WHEN** a full reconciliation for a game returns a set that omits a stored achievement
- **THEN** that achievement is marked as no longer offered and stops contributing to
  counts, totals, and experience

#### Scenario: Rarity snapshot survives retirement
- **WHEN** an achievement is marked as no longer offered
- **THEN** its first-unlock rarity snapshot remains stored, because that value cannot be
  recovered from any source once discarded

#### Scenario: Achievement returns in a later response
- **WHEN** a later refresh includes an achievement previously marked as no longer offered
- **THEN** the mark is cleared and the achievement contributes to counts again, using its
  retained snapshot

#### Scenario: Absence during a partial refresh does not retire
- **WHEN** a refresh covers only part of the library, or does not represent a full view of
  a game's achievement set
- **THEN** no achievement is marked as no longer offered on the basis of that refresh
