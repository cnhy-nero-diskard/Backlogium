## MODIFIED Requirements

### Requirement: Collection persistence
The system SHALL persist custom collections and their game membership locally as app-owned state,
independent of Steam sync payloads. Each collection SHALL store a name, a mode, a sort selection, an
optional description, a display order, and an
optional target completion date. Collection membership SHALL reference games by Steam app id. Because
collections are app-owned and absent from Steam's payload, they SHALL survive every sync poll without
being reset, dropped, or reordered.

#### Scenario: Collection survives a sync
- **WHEN** a Steam sync poll rebuilds the games table
- **THEN** all collections and their members remain intact, unchanged, and in their stored display
  order

#### Scenario: Member references a game absent from the library
- **WHEN** a collection member references an app id that has no stored game row
- **THEN** that member is omitted from the collection's rendered summary without failing the collection
  or removing the membership row

#### Scenario: Target date stored only for deadline mode
- **WHEN** a collection's mode is not the deadline mode
- **THEN** no target date is required or rendered for it

#### Scenario: Description stored when provided
- **WHEN** a collection is saved with a description
- **THEN** that description is persisted with the collection and survives sync polls

#### Scenario: Collection without a description
- **WHEN** a collection has never been given a description
- **THEN** it stores no description, and that absence is distinguishable from a description the user
  entered and then cleared to empty

## ADDED Requirements

### Requirement: Collection display order
The system SHALL store an explicit display order for collections and SHALL present collections in
that order wherever the full set is listed. The system SHALL provide a means to change the order, and
SHALL persist a changed order so it survives leaving the screen, restarting the app, and every sync
poll. Display order SHALL be independent of a collection's mode, accent, creation time, and member
order.

Collections that existed before a display order was stored SHALL be assigned an initial order
matching the order they were previously presented in, so ordering is unchanged the first time the
stored order takes effect.

#### Scenario: Collections presented in stored order
- **WHEN** the full set of collections is listed
- **THEN** they appear in their stored display order

#### Scenario: Changing the order
- **WHEN** the user moves a collection to a different position
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
