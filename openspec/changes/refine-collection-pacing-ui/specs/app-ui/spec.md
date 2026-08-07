## MODIFIED Requirements

### Requirement: Collections section on the Home screen
The Home screen SHALL present a collections section showing one card per custom collection. Each card SHALL foreground the collection name, one concise mode-relevant status line, and a structured progress surface when applicable without rendering a separate uppercase mode label. The mode icon SHALL remain visually and accessibly identifiable. Cards SHALL use an elevated surface distinct from the Home background, remain visually separated, and use a stored accent to tint card and accent affordances. Tapping a card SHALL open its collection overview. The section SHALL render from locally stored state, present an empty state when no collections exist, and SHALL NOT displace or demote the existing level, XP, quest, streak, or now-playing surfaces.

#### Scenario: Collections shown on Home
- **WHEN** the Home screen is shown and one or more collections exist
- **THEN** a card is shown for each collection with its name, mode icon, and concise mode-relevant state

#### Scenario: Cards visually separated
- **WHEN** two or more collection cards are shown
- **THEN** consecutive cards are separated by visible spacing

#### Scenario: Mode-specific card surfaces
- **WHEN** a collection's mode is completion goal, deadline goal, or ordered queue
- **THEN** its card presents compact structured information relevant to that mode without an uppercase mode heading or a multi-detail sentence

#### Scenario: Healthy deadline card stays quiet
- **WHEN** a reliable complete deadline plan is on track
- **THEN** its Home card shows concise countdown and progress information without buffer or corrective copy

#### Scenario: At-risk deadline card shows required pace
- **WHEN** a reliable complete deadline plan is at risk
- **THEN** its Home card replaces verbose estimate detail with one concise required-pace or attention state

#### Scenario: Incomplete forecast is not expanded on Home
- **WHEN** a collection forecast is incomplete because history or HLTB estimates are missing
- **THEN** Home uses at most a compact incomplete state and leaves the detailed explanation to the collection overview

#### Scenario: Completion-goal trophy summary
- **WHEN** a collection's mode is completion goal and achievement counts exist for one or more members
- **THEN** its Home card may include aggregate trophy progress within its single concise mode-relevant status line

#### Scenario: Accent tint applied
- **WHEN** a collection has a stored accent
- **THEN** its card surface and accent affordances use a low-opacity tint from that accent while its text remains legible

#### Scenario: Cards remain compact
- **WHEN** the Home screen shows multiple collection cards
- **THEN** each card uses compact internal padding and consecutive cards have visible spacing without excessive vertical gaps

#### Scenario: Collection member thumbnail preview
- **WHEN** a Home collection card has one or more members
- **THEN** the card shows up to three small member-game thumbnails in stored member order on the right

#### Scenario: Collection member thumbnail overflow
- **WHEN** a Home collection card has more than three members
- **THEN** the card shows three thumbnails followed by the number of remaining members using the existing `N+` convention, such as `8+` for an eleven-game collection

#### Scenario: Default styling without accent
- **WHEN** a collection has no stored accent
- **THEN** its card presents the default neutral styling

#### Scenario: Opening an existing collection
- **WHEN** the user taps a collection card on Home
- **THEN** a read-only overview of that collection is opened, with its selected games and local collection metrics visible before customization controls

#### Scenario: No collections
- **WHEN** the Home screen is shown and no collections exist
- **THEN** the collections section presents an empty state rather than an empty list

#### Scenario: Offline rendering
- **WHEN** the Home screen is shown without network
- **THEN** the collections section renders from the last stored state without blocking

#### Scenario: Existing Home surfaces preserved
- **WHEN** the collections section is shown on Home
- **THEN** the level, XP, daily-quest, streak, and now-playing surfaces remain present and unchanged

## ADDED Requirements

### Requirement: Active-play collection glow
Every visible Home collection card containing the currently played game's app id SHALL display a faint border glow while that game is active. The glow SHALL use the collection accent when available and the themed live-playing color otherwise, SHALL animate drawing without changing card dimensions, and SHALL fade after play ends. Motion SHALL NOT be the only indication when reduced motion is requested.

#### Scenario: Played game belongs to one collection
- **WHEN** live status identifies a game contained by a visible Home collection
- **THEN** that collection card displays a slow faint pulsating border glow

#### Scenario: Played game belongs to multiple collections
- **WHEN** the active game belongs to more than one visible collection
- **THEN** every matching collection card displays the glow concurrently

#### Scenario: Played game belongs to no collection
- **WHEN** the active game belongs to no visible collection
- **THEN** no collection card displays an active-play glow

#### Scenario: Play ends
- **WHEN** live status transitions from the matching game to not playing
- **THEN** the matching collection glow fades out rather than disappearing in a single frame

#### Scenario: Reduced motion
- **WHEN** the platform requests reduced motion while a matching game is active
- **THEN** the collection card uses a static faint outline or equivalent non-animated cue

### Requirement: Collection game-card background art
Game cards inside collection overview and management surfaces SHALL use the same right-aligned, low-opacity Steam header-art treatment and horizontal fade behavior as Library game cards. The collection accent strip, text, metrics, and controls SHALL remain legible above the artwork. Missing or failed header art SHALL leave a complete themed card surface rather than a broken image state.

#### Scenario: Header art is available
- **WHEN** a collection member has a Steam header-art URL
- **THEN** its collection game card shows the artwork aligned to the right and fading out before the primary text region

#### Scenario: Header art is unavailable
- **WHEN** a collection member has no usable header artwork
- **THEN** its game card retains the normal themed surface without a broken-image placeholder

#### Scenario: Collection controls remain usable
- **WHEN** a management game card contains reorder, done, or remove controls over a bright header image
- **THEN** every control and its state remain visually legible and interactive

#### Scenario: Artwork treatment stays shared
- **WHEN** Library and Collections render game-header backdrops
- **THEN** both use the same shared fade and opacity treatment rather than independently tuned copies

### Requirement: Collection overview Personal Pace presentation
Collection overviews SHALL present Personal Pace detail only for modes that benefit from pacing. They SHALL distinguish reliable forecasts, learning history, and missing estimate data; use approximate human-readable durations; and SHALL show `Change deadline` only when the collection domain marks that action eligible.

#### Scenario: Reliable deadline detail
- **WHEN** a deadline overview has a reliable complete Personal Pace forecast
- **THEN** it shows approximate required pace, recent tracked pace, projected capacity, and on-track or at-risk state

#### Scenario: Learning state
- **WHEN** Personal Pace does not yet have sufficient history
- **THEN** the overview explains that Backlogium is learning from tracked activity and makes no definitive fit claim

#### Scenario: Missing estimate detail
- **WHEN** one or more members lack the applicable HLTB estimate
- **THEN** the overview identifies the incomplete estimate count and makes no definitive fit claim

#### Scenario: Conditional deadline action visible
- **WHEN** the collection domain marks deadline intervention eligible
- **THEN** the overview shows the direct `Change deadline` action

#### Scenario: Conditional deadline action hidden
- **WHEN** the collection domain marks deadline intervention ineligible
- **THEN** the overview does not show the direct `Change deadline` action

#### Scenario: Basic list overview
- **WHEN** the collection mode is basic list
- **THEN** the overview presents no Personal Pace section

