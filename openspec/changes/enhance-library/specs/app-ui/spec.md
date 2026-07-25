## ADDED Requirements

### Requirement: Pinned games
The system SHALL let the user pin and unpin any game in the Library, and SHALL present pinned games
in their own section above all other sections. A pinned game SHALL appear in exactly one section.

#### Scenario: Pinning a game
- **WHEN** the user pins a game
- **THEN** it appears in a pinned section above the other Library sections

#### Scenario: Pinned goal game appears once
- **WHEN** a game that is tagged as a goal is pinned
- **THEN** it appears only in the pinned section, and its row still presents its goal information
  and goal actions

#### Scenario: Unpinning
- **WHEN** the user unpins a game
- **THEN** it returns to the section it would otherwise occupy

#### Scenario: No games pinned
- **WHEN** no games are pinned
- **THEN** no pinned section is shown and the Library appears as it does today

#### Scenario: Pins survive a sync
- **WHEN** a library sync completes
- **THEN** previously pinned games remain pinned

### Requirement: Library search
The system SHALL provide a name search that filters the Library, preserving the section structure
for sections that still contain matches.

#### Scenario: Filtering by name
- **WHEN** the user enters text in the Library search
- **THEN** only games whose names contain that text, ignoring case, are shown

#### Scenario: Sections preserved while filtering
- **WHEN** a filter is active and matches exist in more than one section
- **THEN** each section with matches keeps its heading

#### Scenario: No matches
- **WHEN** a filter matches no games
- **THEN** an empty state explains that no games match, rather than showing a blank list

#### Scenario: Clearing the filter
- **WHEN** the user clears the search
- **THEN** the full Library is shown again

### Requirement: Per-game XP contribution badge
The system SHALL show, for each game in the Library, the total XP that game has contributed to the
player's total — its tapered playtime XP plus the XP from its unlocked achievements — such that the
displayed values are consistent with the player's total XP.

#### Scenario: Showing contributed XP
- **WHEN** a game has contributed XP
- **THEN** its row displays that contribution as a compact badge

#### Scenario: Game with no tracked playtime
- **WHEN** a game has no tracked playtime and no imported history and no unlocked achievements
- **THEN** its badge reflects a zero contribution rather than being derived from lifetime Steam
  playtime

#### Scenario: Contribution is not proportional to lifetime playtime
- **WHEN** a game's lifetime Steam playtime greatly exceeds its tracked playtime, or its playtime
  XP has been tapered
- **THEN** the badge still reflects the game's actual XP contribution, and is labelled as
  contributed XP rather than implying a per-hour rate

### Requirement: Batch HowLongToBeat refresh progress
The system SHALL show the progress of a running HowLongToBeat batch refresh, including how many
games have been processed out of the total, and a log of each processed game with its outcome.

#### Scenario: Progress while refreshing
- **WHEN** a batch refresh is running
- **THEN** the Library shows the number of games processed out of the total as a progress indicator

#### Scenario: Per-game log
- **WHEN** each game in the batch is processed
- **THEN** a log entry names the game and its outcome: matched, needs review, no match, or lookup
  failed

#### Scenario: Progress survives leaving the screen
- **WHEN** the user leaves the Library while a refresh is running and returns
- **THEN** the progress indicator reflects the refresh's current position, and the log resumes from
  that point rather than showing entries from before the screen was left

#### Scenario: Refresh completes
- **WHEN** the batch refresh finishes
- **THEN** the progress indicator resolves and the controls become available again

### Requirement: Targeted HowLongToBeat refresh
The system SHALL let the user select multiple games in the Library and run a HowLongToBeat refresh
over only that selection.

#### Scenario: Entering selection mode
- **WHEN** the user long-presses a game row
- **THEN** selection mode is entered with that game selected, and the number of selected games is
  shown

#### Scenario: Refreshing the selection
- **WHEN** the user runs the HowLongToBeat lookup on a selection
- **THEN** only the selected games are refreshed

#### Scenario: Selection preserved while filtering
- **WHEN** a search filter hides a selected game
- **THEN** it remains part of the selection, and the visible selected count continues to include it

#### Scenario: Leaving selection mode
- **WHEN** the user clears the selection or navigates away
- **THEN** selection mode is exited and no selection is retained

#### Scenario: Tap behavior unchanged
- **WHEN** the user taps a game row while not in selection mode
- **THEN** the game's detail screen opens as it does today
