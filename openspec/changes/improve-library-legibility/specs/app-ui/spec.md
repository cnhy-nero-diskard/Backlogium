## ADDED Requirements

### Requirement: Trophy progress renders the existing achievement-count field
Game surfaces SHALL display an achievement-progress bar alongside the trophy fraction wherever their current density exposes the achievement-count field. Existing field subsets, HLTB progress, XP placement, and recency treatments SHALL remain intact; the compact grid SHALL gain no trophy field.

#### Scenario: List and ordinary grid
- **WHEN** a known in-progress trophy count is shown in LIST or GRID
- **THEN** the count remains and a trophy bar accompanies it

#### Scenario: Compact grid
- **WHEN** the compact density hides the trophy count
- **THEN** it also hides the trophy bar and retains the strict field subset

#### Scenario: Collection member count
- **WHEN** a collection member surface exposes the same trophy-count field
- **THEN** it uses the same bar/count state rules rather than a different completion interpretation

### Requirement: Trophy progress remains distinct from HLTB and missing data
Achievement bars SHALL be distinguishable from HLTB progress by more than color and SHALL expose separate labelled accessibility semantics. Missing counts and confirmed no-achievements games SHALL show no trophy bar; known zero unlocks with a positive total SHALL show zero progress. Full known completion SHALL retain the existing completed indicator without a redundant full bar.

#### Scenario: Known zero
- **WHEN** a game has zero unlocked out of a confirmed positive total
- **THEN** an empty trophy track and 0/N fraction are shown

#### Scenario: Unknown schema
- **WHEN** no trustworthy achievement total is stored
- **THEN** no 0/0 or empty progress track is fabricated

#### Scenario: Fully unlocked
- **WHEN** all achievements in a known positive total are unlocked
- **THEN** the completed indicator remains and the trophy bar is omitted

#### Scenario: Adjacent HLTB overrun
- **WHEN** HLTB progress shows an overrun beside achievement progress
- **THEN** both remain distinguishable by their label/icon/layout and accessible descriptions

### Requirement: Library section counts describe the actual matching partitions
Focus and Your games headings SHALL state their actual displayed/matching counts. Active search/filter states SHALL disclose matching versus baseline counts where the section is shown. Focus and ordinary games SHALL remain disjoint, and existing omission/recovery behavior for empty sections SHALL be preserved.

#### Scenario: Unfiltered sections
- **WHEN** ordinary Library sections contain visible games
- **THEN** each shown heading states its own section size

#### Scenario: Filtered sections
- **WHEN** a filter narrows a section
- **THEN** the displayed count follows the actual results and its baseline denominator is clear

#### Scenario: Empty matching section
- **WHEN** a section has no matches
- **THEN** its existing omitted-header/empty-results behavior remains usable without introducing an empty grid gap

### Requirement: Library scale has one explicit cached scope across surfaces
Analytics and Settings Data & privacy SHALL expose a shared all-time count of distinct cached ordinary-library entries before hiding/search/filtering, with visible/hidden scope stated. This universe SHALL include admitted owned/shared entries and exclude the separately presented wishlist and removed-shared archive. Copy SHALL identify the app's cached view rather than claiming an authoritative purchased-game total.

#### Scenario: Hidden games
- **WHEN** some cached entries are hidden
- **THEN** cached and visible/hidden scopes remain understandable rather than silently shrinking the cached count

#### Scenario: Shared becomes owned
- **WHEN** one admitted shared entry becomes owned
- **THEN** the distinct cached count does not increase for the same app ID

#### Scenario: Analytics selected period
- **WHEN** a selected activity period is visible near the library-scale summary
- **THEN** the all-time cached scope remains distinct from that period's activity

#### Scenario: Separate wishlist
- **WHEN** wishlist items are shown elsewhere
- **THEN** their count is separately labelled and is not implicitly added to ordinary-library scale

#### Scenario: Home
- **WHEN** Home displays its current progress/collection content
- **THEN** this change adds no library-scale count there
