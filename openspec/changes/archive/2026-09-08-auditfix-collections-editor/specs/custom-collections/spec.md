# custom-collections

## ADDED Requirements

### Requirement: An editor's buffered changes commit as one unit
Where a collection editor buffers changes in memory and offers a single save action, that save
SHALL commit the collection's own fields and its reconciled membership — additions, removals,
sequence order, and done marks — as one atomic unit. A failure or an interruption part-way
through SHALL leave the stored collection as it was before the save, not partly updated.

This symmetry is the point rather than a nicety: an editor whose cancel discards everything
in memory has told the user that the edit is a transaction. A save that commits its steps
independently breaks that promise in the direction the user cannot see, since a partial
result looks like a successful save until the collection is reopened.

When a save fails, the editor SHALL leave its busy indication and SHALL remain usable, so a
failure is recoverable without recreating the screen.

#### Scenario: Interruption leaves no partial edit
- **WHEN** a save is interrupted after some of its writes would individually have committed
- **THEN** reopening the collection shows the state from before the save, with no subset of
  the changes applied

#### Scenario: Removals and done marks are not lost
- **WHEN** a save adds members, removes others, reorders, and changes done marks, and is
  interrupted after the additions
- **THEN** none of the changes are stored, rather than the additions being durable while the
  removals and done marks are not

#### Scenario: A failed save releases the editor
- **WHEN** a save fails
- **THEN** the editor stops indicating that it is saving and the save action can be used again

#### Scenario: A successful save commits everything
- **WHEN** a save completes successfully
- **THEN** the collection's fields, its membership, its sequence order, and its done marks all
  reflect the buffered edit

#### Scenario: Cancel remains all-or-nothing
- **WHEN** the user cancels an edit
- **THEN** nothing is stored, unchanged by this requirement

## MODIFIED Requirements

### Requirement: Collection member ordering
Each collection SHALL order its members according to a stored sort selection. The available sort selections
SHALL include game name and the metric relevant to the collection's mode. A fresh collection SHALL default
to a sensible order for its mode.

An offered sort selection SHALL order members by the metric it names. A selection whose metric
cannot distinguish members under the current data model SHALL NOT be offered, and SHALL NOT be
satisfied by falling back to a different order while continuing to present the metric's name.
A silent fallback is worse than the absent option: it reports an ordering the members are not
in.

Where a stored sort selection is no longer available, the collection SHALL fall back to its
mode's default order rather than failing to load.

#### Scenario: Sorting by name
- **WHEN** a collection's sort selection is name
- **THEN** members are ordered alphabetically by game name

#### Scenario: Ordered-queue uses manual order
- **WHEN** a collection's mode is ordered queue
- **THEN** members are ordered by their sequence order regardless of the sort selection

#### Scenario: Default sort per mode
- **WHEN** a collection is created
- **THEN** its sort selection defaults to a sensible order for its mode: name for basic, and
  completion fraction for completion goal and for deadline goal, and manual sequence for
  ordered queue

#### Scenario: No sort reports an order it does not produce
- **WHEN** a sort selection is offered to the user for a non-queue collection
- **THEN** the members are ordered by the metric that selection names

#### Scenario: A deadline is a property of the collection, not of a member
- **WHEN** a deadline-goal collection's members are ordered
- **THEN** they are not ordered by a per-member deadline metric, because the target date is
  stored on the collection and is therefore identical for every member

#### Scenario: An unavailable stored sort falls back to the mode default
- **WHEN** a collection has a stored sort selection that the current build does not offer
- **THEN** it is ordered by its mode's default order and continues to load normally
