## MODIFIED Requirements

### Requirement: Collection display order
The system SHALL store an explicit display order for collections and SHALL present collections in
that order wherever the full set is listed. The system SHALL provide a means to change the order,
and SHALL persist a changed order so it survives leaving the screen, restarting the app, and every
sync poll. A changed order SHALL be persisted only when the user completes a reorder gesture by
releasing the dragged card; a cancelled or abandoned drag SHALL leave the stored order unchanged
and SHALL leave the in-memory presentation consistent with the stored order, so a reorder the user
saw started but did not commit does not appear to land and then revert. Display order SHALL be
independent of a collection's mode, accent, creation time, and member order.

Collections that existed before a display order was stored SHALL be assigned an initial order
matching the order they were previously presented in, so ordering is unchanged the first time the
stored order takes effect.

#### Scenario: Collections presented in stored order
- **WHEN** the full set of collections is listed
- **THEN** they appear in their stored display order

#### Scenario: Changing the order
- **WHEN** the user moves a collection to a different position and releases the drag
- **THEN** the new order is persisted and every subsequent listing uses it

#### Scenario: Order survives a restart
- **WHEN** the user reorders collections and the app is restarted
- **THEN** the collections are listed in the reordered sequence

#### Scenario: Order survives a sync
- **WHEN** a Steam sync poll runs after the user has reordered collections
- **THEN** the stored display order is unchanged

#### Scenario: A new collection joins the order
- **WHEN** the user creates a collection
- **THEN** it receives a position in the display order without disturbing the relative order of
  existing collections

#### Scenario: Deleting a collection leaves the rest ordered
- **WHEN** a collection is deleted
- **THEN** the remaining collections keep their relative order with no gap that affects presentation

#### Scenario: Existing collections keep their previous order
- **WHEN** collections created before display order was stored are first listed afterwards
- **THEN** they appear in the same order they were presented in previously, rather than in an
  arbitrary or reversed order

#### Scenario: Order independent of member order
- **WHEN** the user reorders members within an ordered-queue collection
- **THEN** the collections' own display order is unaffected

#### Scenario: Cancelled drag leaves order unchanged
- **WHEN** the user picks up a collection card to reorder and the drag is cancelled or abandoned
  before a clean release
- **THEN** the stored display order is unchanged and the presented order matches that stored order,
  with no stale in-memory reorder that would revert on the next visit

#### Scenario: Completed drag persists across reopen
- **WHEN** the user completes a reorder by releasing the dragged card and later closes and reopens
  the Home screen
- **THEN** the collections are presented in the order the user committed, rather than reverting to a
  prior order
