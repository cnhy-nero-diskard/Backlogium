## ADDED Requirements

### Requirement: Manual queue completion
The system SHALL let the user mark a member of an ordered-queue collection as done and SHALL persist
that mark per membership. A member SHALL count as complete for queue purposes when it is marked done
or when it is fully complete by stored playtime signals. Done marks SHALL be ordered-queue state:
when a collection's mode is not ordered queue, stored marks SHALL NOT affect its ordering or banner.

#### Scenario: Marking a member done
- **WHEN** the user marks a member of an ordered-queue collection as done
- **THEN** the mark is persisted and the member counts as complete for the queue

#### Scenario: Unmarking a member
- **WHEN** the user removes the done mark from an ordered-queue member
- **THEN** the member is again eligible to be presented as the next game

#### Scenario: Done mark inert outside queue mode
- **WHEN** a collection's mode is not ordered queue
- **THEN** stored done marks do not change its member ordering or its banner

#### Scenario: Done marks survive a sync
- **WHEN** a Steam sync poll rebuilds the games table
- **THEN** all stored done marks remain intact

### Requirement: Collection accent color
Each collection SHALL store an optional accent color chosen from an expanded set of app-palette
tokens (steel blue, violet, sage, slate, teal, rose, and coral), and the absence of a choice SHALL
present as the default neutral styling. The offered set SHALL
exclude palette tokens with reserved meaning (the milestone gold and the live-presence green). An
unrecognized stored accent value SHALL fall back to the default rather than fail.

#### Scenario: Accent stored on the collection
- **WHEN** the user selects an accent for a collection
- **THEN** the choice is persisted with the collection

#### Scenario: No accent chosen
- **WHEN** a collection has no stored accent
- **THEN** it is presented with the default neutral styling

#### Scenario: Accent survives a sync
- **WHEN** a Steam sync poll rebuilds the games table
- **THEN** the collection's stored accent remains unchanged

#### Scenario: Unknown accent value
- **WHEN** a collection's stored accent is not a recognized palette token
- **THEN** the collection is presented with the default neutral styling

### Requirement: Completion-goal trophy summary
For a completion-goal collection, the system SHALL aggregate unlocked and total achievement counts
across members with stored achievement data. The Home banner SHALL show the aggregate as
`<unlocked>/<total> trophies · <remaining> left`; when no member has stored achievement data, it
SHALL show a no-data state rather than treating missing data as zero unlocked trophies.

#### Scenario: Trophy counts are aggregated
- **WHEN** a completion-goal collection has members with stored achievement counts
- **THEN** its banner shows the aggregate unlocked count, total count, and remaining count

#### Scenario: Trophy counts have no data
- **WHEN** no member in a completion-goal collection has stored achievement counts
- **THEN** its banner shows that trophy data is unavailable

### Requirement: Collection overview metrics
An existing collection SHALL expose a read-only overview before its management form. The overview
SHALL foreground the collection's selected members and SHALL derive local metrics from cached app
state: library playtime, stored achievement counts, and synthesized session counts. Missing trophy
data SHALL remain distinguishable from zero trophies. Customization, including adding members,
SHALL remain available through an explicit secondary action rather than appearing in the overview.

#### Scenario: Overview shows selected members first
- **WHEN** the user opens an existing collection
- **THEN** the collection's selected games are presented as the primary content before edit controls

#### Scenario: Overview shows member metrics
- **WHEN** a selected game has cached library, achievement, or session data
- **THEN** its overview tile shows playtime and session count, plus unlocked/total trophies when
  achievement counts are stored

#### Scenario: Overview aggregates collection metrics
- **WHEN** an existing collection overview is shown
- **THEN** its summary reports total selected games, aggregate playtime, aggregate sessions, and
  aggregate unlocked/total trophies when at least one member has stored achievement counts

#### Scenario: Overview keeps customization secondary
- **WHEN** the user wants to change collection settings or add games
- **THEN** the user opens the secondary customization action and the buffered management form
  provides those controls

## MODIFIED Requirements

### Requirement: Ordered-queue sequencing
The system SHALL sequence an ordered-queue collection's members by a stored sequence order, and SHALL
expose the first member not marked done as the next game to act on. The user SHALL be able to reorder
members, which SHALL update their sequence order.

#### Scenario: Next game is the first in sequence
- **WHEN** an ordered-queue collection has one or more members and its first member is not marked done
- **THEN** the banner presents the first member in sequence as the next game

#### Scenario: Next game skips done members
- **WHEN** the leading members of an ordered-queue collection are marked done and a later member is not
- **THEN** the banner presents the first member in sequence that is not marked done as the next game

#### Scenario: Reordering members
- **WHEN** the user reorders members in an ordered-queue collection
- **THEN** the sequence order is updated and the next-game surface reflects the new first member not
  marked done

#### Scenario: Queue completed
- **WHEN** every member of an ordered-queue collection is marked done or fully complete
- **THEN** the banner reflects that there is no next game to act on

#### Scenario: Non-queue modes ignore sequence order
- **WHEN** a collection's mode is not ordered queue
- **THEN** members are ordered by the collection's sort selection rather than the sequence order
