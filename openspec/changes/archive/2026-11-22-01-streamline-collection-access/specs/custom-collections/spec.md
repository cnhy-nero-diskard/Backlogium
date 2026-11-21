## ADDED Requirements

### Requirement: Favorites are persistent game preferences
Favorites SHALL be stored per tracked Steam app ID, work offline, and survive sync and shared-to-owned conversion. Favorite writes SHALL leave Focus, XP, and custom membership intact. Hidden or temporarily absent games SHALL retain their values and stay out of visible Favorites. Account reset SHALL clear the previous account's preferences.

#### Scenario: Favorite survives sync and ownership conversion
- **WHEN** a favorited shared game becomes owned and is subsequently synchronized
- **THEN** its favorite remains set without altering Focus or custom membership

#### Scenario: Hidden preference is retained
- **WHEN** a favorite game is hidden and later restored
- **THEN** it disappears from visible Favorites while hidden and returns as a favorite when restored

#### Scenario: Account changes
- **WHEN** the configured Steam identity changes
- **THEN** the previous account's favorite preferences are cleared with its account-owned data

### Requirement: Collection shortcuts preserve membership semantics
Custom collections SHALL support direct add and remove. Duplicate adds SHALL preserve order and done state. Removal SHALL affect only the selected collection, and shortcuts SHALL retain hidden memberships. Uncommitted changes SHALL report failure. An editor save with a stale membership baseline SHALL require retry without overwriting committed shortcuts or partially saving the draft.

#### Scenario: Duplicate add preserves queue state
- **WHEN** a queued game that is already marked done is added again
- **THEN** its membership, position, and done mark remain unchanged

#### Scenario: Removing one membership
- **WHEN** a game is removed directly from one custom collection
- **THEN** its memberships elsewhere, favorite preference, and Focus tag are unchanged

#### Scenario: Target disappears during an action
- **WHEN** a collection is deleted before a pending shortcut commits
- **THEN** no orphan membership is created and the user receives actionable failure feedback

#### Scenario: Editor baseline becomes stale
- **WHEN** a shortcut changes membership while a full editor has an older buffered baseline
- **THEN** saving that stale draft reports a conflict without discarding the committed shortcut member or partially saving the draft
