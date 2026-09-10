## ADDED Requirements

### Requirement: Accepted gap plan creates a deadline collection atomically
The system SHALL create a normal deadline-goal collection when the player confirms a gap-plan
variant. The new collection SHALL use the anticipated title to seed its name, retain the request's
target date and selected HLTB basis, use the deadline mode's valid default sort, and contain exactly
the games accepted in the preview. The collection row and all membership rows SHALL commit as one
atomic unit.

#### Scenario: Story plan accepted
- **WHEN** the player accepts a Story gap plan for an anticipated title and target date
- **THEN** one deadline-goal collection is created with Main Story basis, that target date, a name derived from the anticipated title, and the accepted members

#### Scenario: Completionist plan accepted
- **WHEN** the player accepts a Completionist gap plan
- **THEN** the created deadline-goal collection uses Completionist basis

#### Scenario: Preview membership was edited
- **WHEN** the player removes or replaces suggestions before accepting the plan
- **THEN** the collection contains exactly the final accepted preview membership

#### Scenario: Atomic creation fails
- **WHEN** collection creation is interrupted after some individual writes could have occurred
- **THEN** neither the collection nor any subset of its membership is stored, and the accepted preview remains available for retry

#### Scenario: Accepted plan remains stable
- **WHEN** recommendation metadata or Personal Pace changes after collection creation
- **THEN** the collection retains its accepted membership until the player edits it through normal collection behavior
