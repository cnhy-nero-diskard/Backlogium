## MODIFIED Requirements

### Requirement: Analytics leads with an interpretable insight
Analytics SHALL present a plain-language summary derived from the selected window before secondary chart configuration. It SHALL visually distinguish selected-window figures from all-time figures. Essential insight meaning and its actual displayed period SHALL be visible as text as well as accessible semantics. A featured game's identity and minutes SHALL be visibly scoped to that game, while whole-period totals SHALL be labelled as covering all eligible visible games. Artwork SHALL NOT imply that the featured game owns aggregate figures, is currently being played, or has been completed without evidence.

#### Scenario: Window contains activity
- **WHEN** Analytics has tracked activity in the selected window
- **THEN** the first analytical summary names the dominant useful fact from that window, such as total play, active-day pattern, leading game, or change from the immediately preceding comparable window

#### Scenario: Window contains no activity
- **WHEN** the selected window has no tracked activity
- **THEN** Analytics states that directly, retains period navigation, and does not present all-time achievements or streaks as if they described the empty window

#### Scenario: Featured game and aggregate totals
- **WHEN** the headline features the leading game with artwork and its minutes
- **THEN** visible text identifies its most-played role and displayed period, and the aggregate figures identify their all-games scope separately

#### Scenario: Current calendar period
- **WHEN** a current month or year is only partly elapsed
- **THEN** overview context identifies the represented dates through today without implying future days contributed to activity figures

#### Scenario: Window is changing
- **WHEN** the selected period changes while new figures are loading
- **THEN** retained figures, featured identity/artwork, and scope labels remain attached to the same prior snapshot with an updating state until the replacement snapshot is available

## ADDED Requirements

### Requirement: Featured-game presentation preserves evidence and fallback behavior
Analytics SHALL bind featured identity, artwork, recorded minutes, and displayed scope to the same eligible leading game and snapshot. Missing artwork SHALL preserve the textual identity and meaning. A comparison or no-data headline SHALL NOT retain a stale featured-game panel. Existing deterministic headline priority and ranking, hidden-game exclusions, stored arithmetic, session attribution, and external request behavior SHALL remain unchanged.

#### Scenario: Game artwork unavailable
- **WHEN** the featured game's artwork cannot be loaded
- **THEN** its name, most-played role, minutes, and period remain legible with the established artwork fallback

#### Scenario: Equal leading totals
- **WHEN** multiple games tie under the existing deterministic ranking
- **THEN** the selected game's identity and artwork match its displayed minutes and the presentation makes no unsupported unique-leader claim

#### Scenario: Different headline type
- **WHEN** a comparable-period or empty-window headline replaces a leading-game headline
- **THEN** no stale artwork or game-specific minutes remain attached to its aggregate statement

### Requirement: Analytics scope is readable across accessibility contexts
Visible labels and accessibility descriptions SHALL convey the same featured-game, all-games, selected-period, and lifetime scopes. Localized period text and quantities SHALL remain readable on narrow screens with larger fonts and in both themes. Scope SHALL NOT depend solely on artwork, color, or an explanation available only to assistive technology.

#### Scenario: Screenshot or sighted inspection
- **WHEN** the overview is read without opening an explanation or using a screen reader
- **THEN** the featured game's role and the aggregate figures' scope are still understandable

#### Scenario: Large fonts and assistive technology
- **WHEN** text size is increased or the overview is read by assistive technology
- **THEN** essential scope labels remain available and describe the same facts
