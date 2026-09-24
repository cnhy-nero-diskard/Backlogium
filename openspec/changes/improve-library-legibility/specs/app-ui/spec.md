## ADDED Requirements

### Requirement: Trophy progress is drawn as a bar wherever the achievement count is shown
The system SHALL render a game's unlocked-of-total achievement progress as a proportional bar
wherever that game's achievement count is already shown — the Library list row, the least dense
grid, and the collection overview's member tiles — and SHALL NOT render it anywhere the achievement
count is not shown. The bar SHALL accompany the count rather than replace it.

Because the bar renders the same information category the count does, adding it SHALL NOT change
which information categories any display density shows, and the density ladder's strict-subset
property SHALL continue to hold unchanged.

#### Scenario: Bar on a Library row
- **WHEN** a Library row shows a game's unlocked-of-total achievement count
- **THEN** a proportional bar of that progress is shown with it

#### Scenario: Bar in the least dense grid
- **WHEN** a grid cell shows a game's unlocked-of-total achievement count
- **THEN** a proportional bar of that progress is shown with it

#### Scenario: Bar on a collection member tile
- **WHEN** a collection overview member tile shows a game's unlocked-of-total trophy count
- **THEN** a proportional bar of that progress is shown with it

#### Scenario: No bar where no count is shown
- **WHEN** a game is shown at the densest setting, which shows no achievement count
- **THEN** no trophy bar is shown

#### Scenario: The count is not replaced
- **WHEN** the trophy bar is shown
- **THEN** the unlocked-of-total count remains present beside it

#### Scenario: Density ladder unchanged
- **WHEN** display densities are compared
- **THEN** each denser density's information categories remain a strict subset of the looser one's,
  exactly as before

### Requirement: Trophy progress is visually distinct from completion progress
Where a trophy bar and a HowLongToBeat completion bar appear together, the system SHALL make them
distinguishable by more than colour alone: they SHALL be separated so they do not read as one
control, and each SHALL be identified in its announced description. The trophy bar SHALL NOT use the
accent reserved for milestone moments, the colour used for playtime beyond a completion length, or
the colour reserved for the currently-playing signal.

#### Scenario: Two bars in one cell
- **WHEN** a grid cell shows both a completion bar and a trophy bar
- **THEN** the two are separated and identifiable as two distinct measures rather than one split bar

#### Scenario: Distinguishable without colour
- **WHEN** the two bars are perceived without colour discrimination
- **THEN** which bar is which remains determinable

#### Scenario: Each bar announced
- **WHEN** a game's bars are reached by an accessibility service
- **THEN** each is announced with what it measures and its value

#### Scenario: Reserved colours not used
- **WHEN** the trophy bar is rendered
- **THEN** it does not use the milestone accent, the completion-overrun colour, or the
  currently-playing colour

#### Scenario: Distinct against an overrun completion bar
- **WHEN** a game's playtime exceeds its completion length, so the completion bar shows its overrun
  treatment
- **THEN** the trophy bar beneath it remains unmistakably distinct from both portions of that bar

### Requirement: Trophy progress with no data draws nothing
Where a game has no stored achievement data, the system SHALL draw no trophy bar — not an empty
track and not a zero-width fill — so that missing data remains distinguishable from no achievements
unlocked.

#### Scenario: No stored achievement data
- **WHEN** a game has no stored achievement data
- **THEN** no trophy bar is drawn and the row or cell layout is otherwise unchanged

#### Scenario: Data stored, none unlocked
- **WHEN** a game has stored achievement data and no achievements unlocked
- **THEN** a trophy bar is drawn showing zero progress, distinguishable from a game with no data at
  all

#### Scenario: Game with no achievements
- **WHEN** a game is recorded as having no achievements
- **THEN** no trophy bar is drawn, consistent with no count being shown

### Requirement: A fully-completed game shows its completion indicator instead of a full bar
Where a game's unlocked achievement count equals its total and that total is greater than zero, the
system SHALL present the existing completion indicator and SHALL NOT additionally draw a full trophy
bar, since a full bar and a nearly-full one are not distinguishable at list or grid scale.

#### Scenario: Completed game on a Library row
- **WHEN** a game's achievements are all unlocked
- **THEN** the row shows the completion indicator and no trophy bar

#### Scenario: Completed game in the least dense grid
- **WHEN** a fully-completed game is shown in the least dense grid
- **THEN** the cell shows the completion indicator and no trophy bar

#### Scenario: In-progress game shows the bar
- **WHEN** a game has stored achievement data with any achievement locked
- **THEN** the trophy bar and the count are shown, and no completion indicator

#### Scenario: The completion indicator is unchanged
- **WHEN** a fully-completed game is presented
- **THEN** its existing indicator is presented exactly as it is today

### Requirement: The Library states how many games each of its lists holds
Each Library section SHALL state how many games it holds, alongside its heading, so the library's
scale is answerable without counting. Each section SHALL state its own figure independently.

#### Scenario: Section counts shown
- **WHEN** the Library is shown
- **THEN** each section's heading states how many games that section holds

#### Scenario: Counts follow the sections
- **WHEN** a game is added to or removed from the tracked set
- **THEN** both sections' counts reflect the change

#### Scenario: Counts survive every density
- **WHEN** the Library is shown at any display density
- **THEN** the section counts remain shown

#### Scenario: Counts respect an active search or filter
- **WHEN** a search or filter reduces what a section shows
- **THEN** the section states how many games it is showing and how many it holds in total, rather
  than reporting only the reduced figure

#### Scenario: Empty section
- **WHEN** a section holds no games
- **THEN** its heading is presented consistently with how an empty section is presented today

### Requirement: The library's size is stated where its scale is context
The system SHALL state the size of the library on the Analytics screen, alongside the figures that
describe the library as a whole, and in Settings' Data section. The system SHALL NOT present a
library size on the Home screen, which presents progress content only.

#### Scenario: Size on Analytics
- **WHEN** the Analytics screen is shown while configured
- **THEN** it states how many games the library holds

#### Scenario: Size in Settings
- **WHEN** the Data section of Settings is shown
- **THEN** it states how many games are stored

#### Scenario: Not on Home
- **WHEN** the Home screen is shown
- **THEN** no library size is presented

#### Scenario: Not configured
- **WHEN** Steam credentials are not configured
- **THEN** no library size is presented, consistent with each screen's existing not-configured state

#### Scenario: Surfaces agree
- **WHEN** a library size is presented on more than one surface at the same moment
- **THEN** the figures agree

### Requirement: A library count states what it counted
A presented library size SHALL be conveyed as the library as the app holds it, rather than as an
authoritative count of games owned, since Steam's owned-games response includes tools, utilities,
and playtests alongside games. Where any entries are excluded from a presented list, the surface
SHALL state both the number shown and the number held, rather than presenting only the smaller
figure.

#### Scenario: Count framed as the app's view
- **WHEN** a library size is presented
- **THEN** it is conveyed as what the app holds, not as an authoritative count of games owned

#### Scenario: Excluded entries disclosed
- **WHEN** entries are excluded from a presented list
- **THEN** both the shown count and the total held are stated

#### Scenario: Nothing excluded
- **WHEN** no entries are excluded
- **THEN** a single figure is presented and no exclusion is described

#### Scenario: One derivation behind every count
- **WHEN** a library size is presented on any surface
- **THEN** it derives from the same stored library the other surfaces derive from
