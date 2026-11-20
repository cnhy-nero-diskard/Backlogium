## ADDED Requirements

### Requirement: Favorites membership follows game preferences
The system SHALL derive a read-only Favorites collection from favorite preferences and the visible tracked library. It SHALL include both owned and admitted shared games, exclude wishlist-only and hidden games, and persist no derived membership. Favorites SHALL use the same hide/empty rules and member list on Home and Collections as other derived collections.

#### Scenario: Favorite state changes
- **WHEN** a visible tracked game is favorited or unfavorited
- **THEN** Favorites membership and its displayed count update from the same committed preference

#### Scenario: Preference lacks a tracked game
- **WHEN** a preference references an app ID absent from the visible tracked library
- **THEN** no fabricated member appears and the preference is retained

### Requirement: Played recently uses dated play evidence
The system SHALL derive Played recently from the latest supported Steam last-played timestamp or meaningful observed session instant. Membership SHALL cover today and the preceding thirteen calendar days in the current local zone, exclude future timestamps, and update at local date/zone changes without requiring a sync. Lifetime minutes, manual shared-play estimates, and imported undated playtime SHALL NOT manufacture a recent-play date. A separate live-playing indicator SHALL remain distinct from recent membership.

#### Scenario: Shared game has recent observed play
- **WHEN** an admitted shared game has meaningful session evidence within the fourteen-day window
- **THEN** it appears in Played recently even without Steam-owned playtime

#### Scenario: Window boundaries
- **WHEN** the latest supported play instant is at the start of thirteen days before today
- **THEN** the game is included, while an instant before that boundary or after the present instant is excluded

#### Scenario: Date changes offline
- **WHEN** a date boundary moves a game's latest play outside the window while offline
- **THEN** it leaves Played recently and the count updates without network activity

#### Scenario: Only undated playtime exists
- **WHEN** a game has lifetime or manually entered minutes but no supported recent timestamp
- **THEN** it is not classified as played recently

### Requirement: Derived cards consistently explain their membership
The system SHALL show a clear name, membership rule, coherent cover treatment, and visible-member count for every derived card. Favorites and Played recently SHALL precede the existing Quick wins, Never started, Almost done, Dropped, and Completed order on both Home and Collections. Counts and opened contents SHALL use the same visible membership. Presentation changes SHALL preserve existing membership rules, read-only behavior, hide preferences, empty-list suppression, and placement below custom collections.

#### Scenario: Card and contents agree
- **WHEN** a derived card is opened after a game is hidden or its membership changes
- **THEN** the card count and resulting list reflect the same visible derived membership

#### Scenario: Existing derived rules remain intact
- **WHEN** the revised cards are displayed
- **THEN** their existing thresholds and completion disclosures remain readable and membership is not editable
