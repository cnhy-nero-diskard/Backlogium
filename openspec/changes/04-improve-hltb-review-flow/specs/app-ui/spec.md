## ADDED Requirements

### Requirement: HLTB review session deferral and forward progression
The HLTB Match Center SHALL let the player defer an unresolved game for the current review session.
Explicit Skip and moving past an unresolved game SHALL defer its app ID without changing its stored
match status. Automatic forward navigation and post-match selection SHALL exclude deferred app IDs
and choose the next unprocessed game by the current queue order. When none remains, the Match Center
SHALL show a completion or exhaustion state rather than wrap to a deferred game. The player SHALL
be able to deliberately review skipped games again. The session SHALL survive configuration
recreation and temporary detail return, and SHALL end when the Match Center route is dismissed or
the app process ends.

#### Scenario: Explicit skip
- **WHEN** the player chooses Skip for this review session on an unresolved game
- **THEN** that game remains unresolved in storage, is excluded from automatic forward selection,
  and the next unprocessed game is shown

#### Scenario: Navigate past an unresolved game
- **WHEN** the player uses Next or Previous to leave an unresolved game
- **THEN** the game is deferred for this session and is not selected automatically later in that pass

#### Scenario: Match removes selected game
- **WHEN** the selected game is matched or leaves the queue after a data update
- **THEN** selection moves forward to the next unprocessed app ID in the current actionable order

#### Scenario: Queue partitions reorder
- **WHEN** a game changes between ambiguous and unmatched partitions while review is in progress
- **THEN** selection follows the intended app ID and deferred games remain excluded from automatic
  progression

#### Scenario: No unprocessed games remain
- **WHEN** all actionable games have been matched or deferred
- **THEN** the Match Center shows completion or exhaustion with a deliberate Review skipped action
  when deferred games remain, without wrapping automatically

#### Scenario: Review skipped games
- **WHEN** the player chooses Review skipped
- **THEN** deferred games become selectable for another pass without changing their stored match
  status until the player resolves one

#### Scenario: Session survives temporary interruption
- **WHEN** configuration recreation occurs or the player temporarily opens detail and returns
- **THEN** current selection and the deferred app IDs remain part of the same review session

#### Scenario: Session ends on route dismissal
- **WHEN** the player leaves the Match Center and later opens a new Match Center route
- **THEN** no game remains deferred solely because of the prior review session

#### Scenario: Scoped game is deferred
- **WHEN** the Match Center was opened for one game and the player defers that game
- **THEN** the scoped game stays unresolved and the route offers completion or Review skipped;
  automatic return to Library occurs only when that scoped game is actually resolved

### Requirement: HLTB reviewer entry points across Library and Settings
Library list and both grid densities SHALL offer an accessible per-game completion-time lookup or
review action that reaches the existing targeted HLTB route. Settings Gameplay SHALL offer a direct
Match Center shortcut even when no attention badge is present.

#### Scenario: Per-game action in both grids
- **WHEN** the player uses either Library grid density
- **THEN** each eligible game has a labeled, accessible completion-time lookup or review action
  with the same target game behavior as the list

#### Scenario: Settings Gameplay shortcut
- **WHEN** the player opens Settings Gameplay
- **THEN** a Match Center shortcut opens the general reviewer regardless of attention count

#### Scenario: Targeted route remains scoped
- **WHEN** a per-game Library action opens the reviewer
- **THEN** that game's app ID is the initial review target, and resolving a different game does not
  falsely complete the scoped route
