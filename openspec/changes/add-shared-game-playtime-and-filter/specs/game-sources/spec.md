## MODIFIED Requirements

### Requirement: Tracked time for a shared game is disclosed as observed, not total
Where playtime for a family-shared game is presented, the system SHALL convey that it reflects
only play the app observed — plus any manual estimate the player has set — and SHALL NOT present
it as the player's complete or Steam-verified time in that game.

#### Scenario: Viewing a shared game's playtime
- **WHEN** a family-shared game's tracked playtime is shown
- **THEN** it is presented as the time the app observed rather than as a Steam total

#### Scenario: Play while unobserved
- **WHEN** a family-shared game is played while the app is neither in the foreground nor monitoring
  presence in the background
- **THEN** no session is derived for that play, and the game's tracked time is unchanged

#### Scenario: The remedy is offered
- **WHEN** the disclosure is shown and background presence monitoring is not enabled
- **THEN** the player is pointed at the setting that would improve coverage

#### Scenario: A manual estimate is included but still not claimed as verified
- **WHEN** a family-shared game has a manual playtime estimate set
- **THEN** the presented time includes it, and the disclosure still does not claim the figure is a
  Steam-verified total

## ADDED Requirements

### Requirement: Player can set a manual playtime estimate for a family-shared game
The system SHALL let the player set a manual playtime estimate, in hours, for a family-shared game
from its detail screen, stored as a minutes offset additive to tracked time. The system SHALL let
the player change or clear a previously set estimate at any time — it is not a one-time action. The
system SHALL NOT offer this action for an owned game.

#### Scenario: Setting an estimate for the first time
- **WHEN** the player enters an hours estimate for a family-shared game with no prior estimate
- **THEN** it is stored as a minutes offset for that game, additive to its tracked time

#### Scenario: Editing a previously set estimate
- **WHEN** the player enters a new hours estimate for a family-shared game that already has one
- **THEN** the stored offset is replaced by the new value, not added to the old one

#### Scenario: Clearing an estimate
- **WHEN** the player clears a family-shared game's manual estimate
- **THEN** its stored offset returns to zero and its playtime reflects only tracked time

#### Scenario: Not offered for an owned game
- **WHEN** the player views an owned game's detail screen
- **THEN** no manual playtime action is offered, and its playtime is unaffected by this capability

#### Scenario: Independent of the owned-game history backfill
- **WHEN** the player imports or resets the whole-library Steam-history backfill
- **THEN** no family-shared game's manual estimate is added, changed, or cleared by that action

### Requirement: Manual estimate counts on the same terms as tracked time
The system SHALL include a family-shared game's manual playtime estimate everywhere its tracked
time is counted — XP, derived-collection membership, and completion-progress display — additive
with tracked time, on the same terms the existing owned-game history backfill already counts
alongside an owned game's tracked time.

#### Scenario: Manual estimate contributes to XP
- **WHEN** XP is recomputed for a family-shared game with a manual estimate set
- **THEN** the estimate's minutes are included in that game's playtime input to XP, tapered the
  same way tracked minutes are

#### Scenario: Manual estimate affects derived-collection membership
- **WHEN** a family-shared game's tracked time plus its manual estimate crosses a derived
  collection's playtime threshold (e.g. Completed by playtime, Almost Done, Dropped)
- **THEN** the game's membership reflects the combined total, exactly as it would for an owned
  game's combined backfill-plus-tracked total

#### Scenario: Manual estimate affects the completion-progress display
- **WHEN** a family-shared game's completion progress against its HowLongToBeat length is shown
- **THEN** the manual estimate is included in the playtime the progress is computed from

#### Scenario: Clearing the estimate does not affect tracked sessions
- **WHEN** the player clears a family-shared game's manual estimate
- **THEN** its tracked sessions and their history are unchanged — only the manual offset is removed

### Requirement: Credited manual time survives shared-to-owned conversion
When a family-shared game with a manual estimate is reported as owned, the system SHALL fold the
credited manual minutes into that game's owned-history offset (`backfillMinutes`) and reset the
manual offset to zero in the same atomic conversion, so the game's XP/playtime credit does not
fall merely because ownership changed. The converted game is an owned game afterwards: the manual
editor is not offered for it, and its owned display/derived totals keep reading Steam's lifetime
total (which already includes the borrowed hours) rather than adding the preserved offset on top.

#### Scenario: Conversion preserves XP credit
- **WHEN** a family-shared game with nonzero tracked minutes and a nonzero manual estimate is
  converted to owned and XP is recomputed
- **THEN** that game's XP input equals its pre-conversion `tracked + manual` credit (now held as
  `backfill + tracked`), and total XP does not drop on account of the conversion

#### Scenario: Preservation holds for an already-imported profile
- **WHEN** the profile's one-time Steam-history import already ran (so no future import will
  restore a dropped estimate) and a family-shared game with a manual estimate is converted
- **THEN** the converted game's preserved offset is present without any further import, and a
  later recompute still credits it

#### Scenario: No double counting after conversion
- **WHEN** the converted game's owned playtime is displayed or its derived-collection membership
  is evaluated
- **THEN** Steam's reported lifetime total is used (or the greater of it and the preserved
  history-plus-tracked fallback), so the borrowed hours are not counted twice

### Requirement: Manual estimate is bounded and combined without overflow
The system SHALL reject a manual estimate above 6,000,000 minutes (100,000 hours) at both the
hours-entry boundary and the write path, and SHALL combine `backfill + manual + tracked` (and
`tracked + manual` display sums) in a wider type clamped to `Int.MAX_VALUE` rather than wrapping
on overflow, so a valid estimate plus any tracked time cannot corrupt XP or display.

#### Scenario: Oversized estimate is rejected
- **WHEN** the player enters hours converting to more than 6,000,000 minutes, or the write path
  is called with more than 6,000,000 minutes
- **THEN** the value is rejected (no write, no recompute) rather than stored

#### Scenario: Near-limit combination does not overflow
- **WHEN** a stored estimate plus tracked minutes would exceed `Int.MAX_VALUE`
- **THEN** the combined XP/display input is clamped to `Int.MAX_VALUE` rather than wrapping to a
  negative or small value

### Requirement: Library can be filtered to family-shared games only
The system SHALL let the player filter the Library to show only family-shared games, alongside
existing filters (genre, HowLongToBeat coverage), applied to every section those filters already
apply to.

#### Scenario: Filtering to family-shared games
- **WHEN** the player enables the family-shared filter
- **THEN** only family-shared games remain visible in the filtered sections

#### Scenario: Clearing the filter
- **WHEN** the player disables the family-shared filter
- **THEN** owned games reappear alongside family-shared ones

#### Scenario: Combined with other active filters
- **WHEN** the family-shared filter is active alongside a genre or coverage filter
- **THEN** only games matching all active filters are shown

#### Scenario: No family-shared games in the library
- **WHEN** the family-shared filter is enabled and the library has no family-shared games
- **THEN** the filtered sections show the existing no-matches empty state, not an error
