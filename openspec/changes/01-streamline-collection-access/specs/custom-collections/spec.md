## ADDED Requirements

### Requirement: Favorites are persistent game preferences
The system SHALL persist a favorite value per tracked Steam app ID as app-owned state, independent of Focus and custom membership. Setting or clearing a favorite SHALL work offline, survive ordinary sync and shared-to-owned conversion, and leave XP and Focus unchanged. Hiding or temporarily omitting a game SHALL retain its preference while excluding it from visible favorites. Changing the configured account SHALL clear the previous account's preferences through the existing account-change protocol.

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
The system SHALL support direct add/remove operations for custom collections without requiring a full editor save. Adding an existing member SHALL be a no-op that preserves its queue position and done mark. Removing a member SHALL affect only that collection. Shortcuts SHALL preserve retained hidden memberships and SHALL report failure without presenting an uncommitted change as successful. A full editor save whose membership baseline has changed concurrently SHALL require refresh/retry rather than silently overwriting committed shortcut changes.

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
