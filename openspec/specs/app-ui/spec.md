# app-ui

## Purpose

Defines the Android app's UI behavior: visual theming, typography, iconography,
game art states, celebratory animations, the Library screen, and sync feedback on
Home.

## Requirements

### Requirement: Custom dark visual theme
The system SHALL render all screens using a hand-authored dark color scheme (charcoal/
navy surfaces with a single gold/amber accent) and SHALL NOT use Android dynamic
(wallpaper-derived) color, so the app's appearance is identical across devices.

#### Scenario: Consistent appearance regardless of device wallpaper
- **WHEN** the app is launched on an Android 12+ device with dynamic color available
- **THEN** the app renders using the custom color scheme, not a wallpaper-derived one

#### Scenario: Consistent appearance across OS versions
- **WHEN** the app is launched on a device below Android 12
- **THEN** the app renders using the same custom color scheme used on newer devices

### Requirement: Display typography for numeral moments
The system SHALL render level number, streak count, and XP total text using a distinct
display font, and SHALL render all other text using a bundled brand body font. No text
SHALL fall back to the platform system default font.

#### Scenario: Level number uses display font
- **WHEN** the Home screen shows the player's level number
- **THEN** it is rendered in the display font, not the body font or the system default font

#### Scenario: Body text uses the brand body font
- **WHEN** any screen renders body, caption, title, or label text (e.g. section
  headers, list rows, dialog text, navigation labels)
- **THEN** it is rendered in the bundled brand body font, not the platform system default font

#### Scenario: Offline availability preserved
- **WHEN** the app runs with no network connectivity
- **THEN** both the display font and the brand body font render from bundled resources,
  with no downloadable-font dependency

### Requirement: App-wide icon set
The system SHALL represent navigation destinations and status indicators using icons
from a single icon library, and SHALL NOT use emoji characters for these purposes.

#### Scenario: Navigation bar icons
- **WHEN** the bottom navigation bar is shown
- **THEN** Home, Library, and History are each represented by an icon from the chosen
  icon library instead of an emoji character

#### Scenario: Status glyphs
- **WHEN** a status indicator is shown (streak, quest completion state)
- **THEN** it is represented by an icon from the chosen icon library instead of an
  emoji character

### Requirement: Game art loading and error states
The system SHALL display a themed placeholder while a game's icon image is loading,
and a themed fallback icon if the image fails to load, on both the Library screen's
goal and backlog game rows.

#### Scenario: Icon still loading
- **WHEN** a game row is displayed and its icon image has not finished loading
- **THEN** a themed placeholder is shown in place of the icon

#### Scenario: Icon fails to load
- **WHEN** a game row's icon image fails to load (network error or invalid URL)
- **THEN** a themed fallback icon is shown instead of a blank space

### Requirement: Celebratory inline animations
The system SHALL play an inline animation within the Home screen's Level card when the
player's level increments, and SHALL play an inline animation within the Home screen's
Streak card when the current streak reaches a milestone interval of every 7 days.

#### Scenario: Level increments
- **WHEN** the player's level increases from its previous value
- **THEN** an inline animation plays within the Level card

#### Scenario: Streak reaches a weekly milestone
- **WHEN** the current streak's day count is a positive multiple of 7
- **THEN** an inline animation plays within the Streak card

#### Scenario: Streak not at a milestone
- **WHEN** the current streak's day count is not a multiple of 7
- **THEN** no milestone animation plays within the Streak card

### Requirement: Library screen
The system SHALL provide a Library screen separating tagged goal games from backlog
games, and SHALL allow marking a game as a goal and unmarking it. Goal games SHALL be
shown without a manual completion target; goal progress against a completion length is
deferred until a HowLongToBeat-sourced length is available.

#### Scenario: Goal games listed without a manual target
- **WHEN** the Library is shown and goal games exist
- **THEN** each goal game displays its name, icon, and playtime, and does not display a
  manual target value or a target-based progress percentage

#### Scenario: Marking a game as a goal
- **WHEN** the user marks a backlog game as a goal, or unmarks a goal game
- **THEN** the game moves between the goal and backlog sections and the change persists,
  without prompting for a typed target

### Requirement: Sync-in-progress feedback on Home
The system SHALL reflect an in-flight manual sync on the Home screen: while a "Sync now"
poll is running, the trigger control SHALL show a progress indicator and be disabled,
and SHALL return to its idle state when the poll completes.

#### Scenario: Sync in progress
- **WHEN** the user triggers "Sync now" and the poll has not yet completed
- **THEN** the sync control shows a progress indicator and is disabled so it cannot be
  triggered again

#### Scenario: Sync completes
- **WHEN** an in-flight sync completes (successfully or with an error)
- **THEN** the sync control returns to its enabled idle state and the screen reflects the
  updated last-sync time or any sync error
