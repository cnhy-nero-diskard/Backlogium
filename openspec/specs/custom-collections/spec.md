# custom-collections

## Purpose

Defines app-owned custom game collections, their local persistence, mode-specific summaries, and
collection management behavior. Collections are independent of the existing Focus tag and are
surfaced on Home rather than as a new navigation tab.

## Requirements

### Requirement: Collection persistence
The system SHALL persist custom collections and their game membership locally as app-owned state,
independent of Steam sync payloads. Each collection SHALL store a name, a mode, a sort selection, an optional description, a display order, and an
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
The system SHALL support four collection modes - basic list, completion goal, deadline goal, and ordered
queue - each determining the banner the collection presents. The mode SHALL be chosen when the collection
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
The system SHALL derive each collection's banner and pacing values as a pure function of stored signals - cached
HowLongToBeat completion lengths, stored achievement rows, playtime, a Personal Pace forecast, and an injected
current date - with no network calls and no dependency on Android. A member's completion fraction SHALL be its
playtime divided by
its HowLongToBeat completionist length, clamped to 0.0-1.0, matching the definition the gamification
engine's goal-progress uses. A collection's aggregate completion progress SHALL be the mean of its members'
individual completion fractions, considering only members with a known completion length. Achievements
remaining SHALL be the sum of locked achievements across members that have stored achievement data. Forecast
uncertainty SHALL remain distinct from complete, on-track, and at-risk outcomes.

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

#### Scenario: Forecast uncertainty preserved
- **WHEN** the Personal Pace profile is learning or required HLTB estimates are missing
- **THEN** the summary exposes that uncertainty and does not classify a future deadline as on track or at risk

#### Scenario: Empty collection
- **WHEN** a collection has no members
- **THEN** its banner presents an empty state with no derived progress, remaining, countdown, or pacing values

#### Scenario: Derivation issues no network calls
- **WHEN** a collection summary is derived
- **THEN** no Steam or HowLongToBeat network request is issued; only locally stored signals are read

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

### Requirement: Deadline estimate basis and hindsight
A deadline-goal collection SHALL let the user select one HLTB completion-length basis: Main Story
(`comp_main`), Main + Extra (`comp_plus`), Completionist (`comp_100`), or All Styles (`comp_all`).
The selected basis SHALL be persisted with the collection. Its deadline plan SHALL subtract stored
playtime from each member's known selected estimate and compare the remaining minutes with Personal Pace's
projected gaming capacity through the target date. Members without the selected estimate SHALL be identified as
unknown and SHALL NOT be treated as zero minutes. Calendar minutes outside the Personal Pace forecast SHALL NOT
count as playable capacity.

#### Scenario: Selecting the deadline basis
- **WHEN** the user configures a deadline-goal collection
- **THEN** the setup offers all four HLTB bases and persists the selected choice

#### Scenario: Reliable deadline fits
- **WHEN** the Personal Pace profile is reliable, every member has the selected estimate, and projected capacity covers unfinished work through a future target date
- **THEN** the collection is on track and presents no deadline-change recommendation

#### Scenario: Reliable deadline is infeasible
- **WHEN** the Personal Pace profile is reliable, every member has the selected estimate, and unfinished work exceeds projected capacity through a future target date
- **THEN** the collection is at risk and reports the required pace and capacity shortfall

#### Scenario: Forecast is still learning
- **WHEN** Personal Pace lacks sufficient history for a future deadline
- **THEN** the collection may report required known work but does not claim that the deadline fits or is infeasible

#### Scenario: Selected estimate is missing
- **WHEN** one or more members lack the selected HLTB estimate
- **THEN** the collection identifies the missing estimates and does not classify the future deadline as on track or at risk

#### Scenario: Deadline has arrived with unfinished work
- **WHEN** the target date is today or earlier and the non-empty collection still has known or unknown unfinished work
- **THEN** the collection reports that the deadline has arrived or passed and makes deadline intervention eligible regardless of forecast confidence

#### Scenario: Deadline has arrived after completion
- **WHEN** the target date is today or earlier and every member's selected estimated work is complete
- **THEN** the collection is complete and does not recommend changing the deadline

#### Scenario: Changing the deadline from the overview
- **WHEN** the user confirms a new date from the overview shortcut
- **THEN** only the collection target date changes and the deadline plan refreshes without opening
  the full customization form

### Requirement: Mode-aware Personal Pace guidance
The system SHALL apply Personal Pace only to collection modes that have completion or sequencing meaning. Deadline goals SHALL use their selected HLTB basis, completion goals SHALL use Completionist estimates, and ordered queues SHALL use Completionist estimates for the next unfinished member and, when complete data exists, the remaining queue. Basic lists SHALL NOT present a pacing forecast.

#### Scenario: Deadline goal pacing
- **WHEN** a deadline-goal collection has a target date and known selected estimates
- **THEN** it reports required pace through the target and reports feasibility only when history and estimate completeness permit

#### Scenario: Completion goal horizon
- **WHEN** a completion-goal collection has a reliable profile and Completionist estimates for all unfinished members
- **THEN** it reports an approximate completion horizon at the user's Personal Pace

#### Scenario: Ordered queue next-game horizon
- **WHEN** an ordered queue has a reliable profile and its next unfinished member has a Completionist estimate
- **THEN** it reports an approximate horizon for completing that next game

#### Scenario: Ordered queue total horizon requires complete data
- **WHEN** any unfinished queue member lacks a Completionist estimate
- **THEN** no definitive whole-queue completion horizon is reported

#### Scenario: Basic list has no pacing guidance
- **WHEN** a collection's mode is basic list
- **THEN** no Personal Pace forecast is attached to its summary

### Requirement: Conditional deadline intervention
The system SHALL make the direct `Change deadline` action eligible only for a non-empty deadline collection with unfinished or unknown work when the target date is today or earlier, or when a reliable and complete future forecast is at risk. It SHALL keep the action ineligible for on-track, learning, incomplete-data, empty, or completed future plans.

#### Scenario: Future at-risk plan is eligible
- **WHEN** a complete reliable forecast says unfinished work exceeds capacity through a future target
- **THEN** the direct deadline-change action is eligible

#### Scenario: Future on-track plan is ineligible
- **WHEN** a complete reliable forecast says capacity covers unfinished work through a future target
- **THEN** the direct deadline-change action is not eligible

#### Scenario: Uncertain future plan is ineligible
- **WHEN** the profile is learning or required HLTB data is missing for a future target
- **THEN** the direct deadline-change action is not eligible

#### Scenario: Arrived deadline is eligible
- **WHEN** a non-empty collection has unfinished or unknown work and its target is today or earlier
- **THEN** the direct deadline-change action is eligible

#### Scenario: Completed plan is ineligible
- **WHEN** no selected estimated work remains and no member estimate is unknown
- **THEN** the direct deadline-change action is not eligible
