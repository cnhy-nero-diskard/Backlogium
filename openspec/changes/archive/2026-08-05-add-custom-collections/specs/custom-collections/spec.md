## ADDED Requirements

### Requirement: Collection persistence
The system SHALL persist custom collections and their game membership locally as app-owned state,
independent of Steam sync payloads. Each collection SHALL store a name, a mode, a sort selection, and an
optional target completion date. Collection membership SHALL reference games by Steam app id. Because
collections are app-owned and absent from Steam's payload, they SHALL survive every sync poll without
being reset, dropped, or reordered.

#### Scenario: Collection survives a sync
- **WHEN** a Steam sync poll rebuilds the games table
- **THEN** all collections and their members remain intact, unchanged

#### Scenario: Member references a game absent from the library
- **WHEN** a collection member references an app id that has no stored game row
- **THEN** that member is omitted from the collection's rendered summary without failing the collection
  or removing the membership row

#### Scenario: Target date stored only for deadline mode
- **WHEN** a collection's mode is not the deadline mode
- **THEN** no target date is required or rendered for it

### Requirement: Manual game membership
The system SHALL let the user manually add any library game to a collection and remove it, and SHALL allow
a game to belong to multiple collections at once. Membership SHALL be independent of the existing Focus tag:
tagging or untagging a game as Focus SHALL NOT affect its collection membership, and adding or removing a
game from a collection SHALL NOT affect its Focus tag.

#### Scenario: Adding a game to a collection
- **WHEN** the user adds a game to a collection
- **THEN** that game appears among the collection's members

#### Scenario: Removing a game from a collection
- **WHEN** the user removes a game from a collection
- **THEN** that game no longer appears among the collection's members, and its membership in other
  collections is unaffected

#### Scenario: A game in multiple collections
- **WHEN** the user adds the same game to a second collection
- **THEN** the game is a member of both collections independently

#### Scenario: Membership independent of the Focus tag
- **WHEN** a game is added to or removed from a collection
- **THEN** the game's Focus tag is unchanged

### Requirement: Collection modes
The system SHALL support four collection modes — basic list, completion goal, deadline goal, and ordered
queue — each determining the banner the collection presents. The mode SHALL be chosen when the collection
is created and SHALL be stored on the collection.

#### Scenario: Basic list mode
- **WHEN** a collection's mode is basic list
- **THEN** its banner presents the member count without completion, deadline, or sequencing surfaces

#### Scenario: Completion goal mode
- **WHEN** a collection's mode is completion goal
- **THEN** its banner presents aggregate completion progress and achievements remaining across members

#### Scenario: Deadline goal mode
- **WHEN** a collection's mode is deadline goal
- **THEN** its banner presents the days remaining until the target date and the aggregate completion
  progress across members

#### Scenario: Ordered queue mode
- **WHEN** a collection's mode is ordered queue
- **THEN** its banner presents the next game in the sequence and the member's position

### Requirement: Collection summary derivation
The system SHALL derive each collection's banner values as a pure function of stored signals — cached
HowLongToBeat completion lengths, stored achievement rows, playtime, and an injected current date — with no
network calls and no dependency on Android. A member's completion fraction SHALL be its playtime divided by
its HowLongToBeat completionist length, clamped to 0.0–1.0, matching the definition the gamification
engine's goal-progress uses. A collection's aggregate completion progress SHALL be the mean of its members'
individual completion fractions, considering only members with a known completion length. Achievements
remaining SHALL be the sum of locked achievements across members that have stored achievement data.

#### Scenario: Completion progress with HowLongToBeat data
- **WHEN** a completion-goal collection has members with cached completion lengths and playtime
- **THEN** the banner shows the aggregate completion fraction derived from those members

#### Scenario: Member without HowLongToBeat data
- **WHEN** a member has no cached completion length
- **THEN** it contributes no completion fraction, and the aggregate fraction considers only members that do

#### Scenario: Achievements remaining
- **WHEN** a completion-goal or deadline-goal collection has members with stored achievement data
- **THEN** the banner shows the total locked achievements remaining across those members

#### Scenario: Member without achievement data
- **WHEN** a member has no stored achievement data
- **THEN** it contributes zero to achievements remaining and does not fail the derivation

#### Scenario: Deadline countdown
- **WHEN** a deadline-goal collection has a target date
- **THEN** the banner shows the number of days from the injected current date to the target date

#### Scenario: Deadline passed
- **WHEN** a deadline-goal collection's target date is on or before the injected current date
- **THEN** the banner reflects that the deadline has passed rather than showing a negative countdown

#### Scenario: Empty collection
- **WHEN** a collection has no members
- **THEN** its banner presents an empty state with no derived progress, remaining, or countdown values

#### Scenario: Derivation issues no network calls
- **WHEN** a collection summary is derived
- **THEN** no Steam or HowLongToBeat network request is issued; only locally stored signals are read

### Requirement: Ordered-queue sequencing
The system SHALL sequence an ordered-queue collection's members by a stored sequence order, and SHALL
expose the first member as the next game to act on. The user SHALL be able to reorder members, which SHALL
update their sequence order.

#### Scenario: Next game is the first in sequence
- **WHEN** an ordered-queue collection has one or more members
- **THEN** the banner presents the first member in sequence as the next game

#### Scenario: Reordering members
- **WHEN** the user reorders members in an ordered-queue collection
- **THEN** the sequence order is updated and the next-game surface reflects the new first member

#### Scenario: Queue completed
- **WHEN** every member of an ordered-queue collection is fully complete
- **THEN** the banner reflects that there is no next game to act on

#### Scenario: Non-queue modes ignore sequence order
- **WHEN** a collection's mode is not ordered queue
- **THEN** members are ordered by the collection's sort selection rather than the sequence order

### Requirement: Collection member ordering
Each collection SHALL order its members according to a stored sort selection. The available sort selections
SHALL include game name and the metric relevant to the collection's mode. A fresh collection SHALL default
to a sensible order for its mode.

#### Scenario: Sorting by name
- **WHEN** a collection's sort selection is name
- **THEN** members are ordered alphabetically by game name

#### Scenario: Ordered-queue uses manual order
- **WHEN** a collection's mode is ordered queue
- **THEN** members are ordered by their sequence order regardless of the sort selection

#### Scenario: Default sort per mode
- **WHEN** a collection is created
- **THEN** its sort selection defaults to a sensible order for its mode: name for basic, completion
  fraction for completion goal, days remaining for deadline goal, and manual sequence for ordered queue
