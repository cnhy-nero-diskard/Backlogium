## MODIFIED Requirements

### Requirement: Achievement sorting
The game detail screen SHALL let the user sort achievements by date achieved or by rarity independently of its All, Unlocked, or Locked filter. All SHALL render one sorted list without an unlocked-first partition. Date order SHALL be newest first with unknown dates last. Rarity order SHALL use the current global unlock percentage, rarest first with unknown percentages last, and SHALL NOT use the earned snapshot as a substitute sorting key. Equal or unknown keys SHALL have deterministic display-name and achievement-identity ties. Sort/filter state SHALL survive recreation within the same visit and reset on a new detail visit.

#### Scenario: Default order
- **WHEN** the game detail screen is opened for a new visit
- **THEN** All is selected and achievements are ordered by date achieved, most recent first, with unknown dates last

#### Scenario: Sorting by rarity
- **WHEN** the user sorts by rarity
- **THEN** visible achievements are ordered by current global percentage from rarest to most common, with unknown percentages last

#### Scenario: Locked achievements participate in the mixed order
- **WHEN** All is selected and a locked achievement has a lower current global percentage than an unlocked achievement
- **THEN** the locked achievement appears first under rarity sorting without a separate unlocked partition

#### Scenario: Locked achievements grouped last
- **WHEN** date sorting encounters locked achievements without known unlock dates
- **THEN** those rows follow dated rows because unknown dates sort last, not because unlock state defines a partition
- **AND** the former unlocked-first behavior in rarity sorting is superseded by the single mixed current-percentage order

#### Scenario: Sort not persisted
- **WHEN** the user leaves the detail visit and returns for a new visit
- **THEN** the default sort and All filter are applied again

#### Scenario: Filter does not alter sort
- **WHEN** the user changes between All, Unlocked, and Locked
- **THEN** the selected sort is preserved and applied to the filtered rows

### Requirement: Achievement unlock rate
The game detail screen SHALL distinguish the current global unlock percentage from the frozen first-unlock rarity snapshot used for earned tier and XP. When available, the current percentage SHALL be labeled as current and be the value used for rarity sorting for both locked and unlocked achievements. An unlocked row with a stored snapshot SHALL retain a labeled earned rarity value consistent with its earned tier; it SHALL NOT imply that the snapshot is current. Unknown values SHALL remain absent rather than becoming zero or fabricated rates.

#### Scenario: Rate shown for an unlocked achievement
- **WHEN** an unlocked achievement has a stored rarity snapshot
- **THEN** its row retains the labeled earned snapshot consistent with its rarity tier and distinguishes any available current global percentage

#### Scenario: Rate shown for a locked achievement
- **WHEN** a locked achievement has a known global unlock percentage
- **THEN** its row displays that percentage as current without an earned tier or XP claim

#### Scenario: Rate unknown
- **WHEN** an achievement has neither a rarity snapshot nor a known global unlock percentage
- **THEN** its row displays no unlock rate rather than showing a zero or placeholder value

#### Scenario: Rate agrees with the rarity sort
- **WHEN** achievements are sorted by rarity
- **THEN** the order follows the labeled current percentages, and rows without one sort last even if an earned snapshot exists

### Requirement: Game detail artwork fallback
The full game-detail destination opened from Library and the game-detail overlay opened from Collection SHALL render the same wide banner treatment and resolved preferred artwork. Without a user selection, the chain SHALL try `header.jpg`, then `library_hero.jpg`, `capsule_616x353.jpg`, `hero_capsule.jpg`, and `library_600x900.jpg`. A selected supported Steam variant SHALL be tried first, followed by this deduplicated default chain. The detail surface SHALL remain intact if every candidate fails. The surrounding full-detail accent wash SHALL sample the first candidate that decodes successfully, and overlay wash containment SHALL remain unchanged.

#### Scenario: Library game detail uses fallback art
- **WHEN** a Library game detail screen with no user selection cannot load its `header.jpg`
- **THEN** it tries `library_hero.jpg` first, followed by the remaining ordered assets, without changing the banner geometry

#### Scenario: Collection game detail uses the same fallback art
- **WHEN** a Collection game-detail overlay cannot load its preferred artwork
- **THEN** it uses the same selected variant, ordered fallback chain, and banner treatment as the Library detail screen

#### Scenario: Detail artwork is entirely unavailable
- **WHEN** every game-detail artwork candidate fails
- **THEN** the detail card keeps its themed content and the full-detail accent wash remains unset rather than showing a broken-image placeholder

#### Scenario: Selected asset disappears
- **WHEN** a previously selected asset no longer decodes
- **THEN** the next default candidate is used without deleting the stored selection or altering the card geometry

## ADDED Requirements

### Requirement: Detail groups remain readable and truthful
Every game-detail entry point SHALL present labeled identity/metadata, estimates, playtime, and achievement groups with useful screenshot framing. The hierarchy SHALL preserve the Favorites heart, Steam link, shared-game measurement disclosures, hidden-game behavior, imported/tracked distinctions, and current player count's separate status. Missing metadata SHALL NOT become fabricated values. Groups and controls SHALL remain usable at large font scale with accessible touch targets.

#### Scenario: Shared game with incomplete metadata
- **WHEN** a shared game lacks an estimate or lifetime Steam total
- **THEN** the grouped detail still presents known facts and their measurement basis without substituting zeros for missing facts

#### Scenario: Detail recreation and return
- **WHEN** detail is recreated or closed back to Library
- **THEN** local detail content and visit state behave as specified and the existing Library visit context is preserved

### Requirement: Achievement filtering has explicit empty states
Detail SHALL offer All, Unlocked, and Locked filters independently of sorting. Summary totals and completion SHALL describe the full available achievement set rather than the filtered subset. An empty filtered set SHALL be distinguished from missing cached achievement data or an unsuccessful refresh.

#### Scenario: No locked achievements remain
- **WHEN** Locked is selected for a game whose cached achievements are all unlocked
- **THEN** the list reports no locked achievements while retaining the game's full completion counts

### Requirement: Placeholder covers offer a Steam artwork chooser
For tracked games with placeholder cover artwork, detail SHALL offer a labeled chooser previewing supported Steam variants for that app ID. Available previews SHALL be selectable, failed or offline-unavailable candidates SHALL be identified, and a stored selection SHALL remain inspectable/resettable after the placeholder is resolved. Selection SHALL apply consistently to detail and tracked-game cover cards in Library and Collections, using their existing frame geometry; avatar/game-icon thumbnails are unaffected. Reset SHALL explicitly clear the override. Wishlist-only results SHALL NOT receive this app-owned tracked-game control.

#### Scenario: Preview and selection
- **WHEN** a placeholder game's Steam variant successfully previews and is selected
- **THEN** it becomes the preferred cover for that tracked game across cover surfaces and survives navigation and sync

#### Scenario: No candidate is available
- **WHEN** all preview candidates fail or are uncached while offline
- **THEN** the chooser reports their availability without claiming a successful replacement

#### Scenario: Reset selection
- **WHEN** the user selects Reset
- **THEN** the override is cleared and each cover surface resumes its default fallback order
