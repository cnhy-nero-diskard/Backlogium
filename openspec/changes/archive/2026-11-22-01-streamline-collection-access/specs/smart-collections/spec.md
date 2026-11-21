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
Played recently SHALL use the latest nonfuture Steam last-played or meaningful session instant within today and the preceding thirteen local calendar days. Membership SHALL update offline on date or zone changes. Lifetime totals, manual estimates, and undated imports SHALL NOT supply instants. Live-playing status SHALL remain distinct.

#### Scenario: Shared game has recent observed play
- **WHEN** an admitted shared game has meaningful session evidence within the fourteen-day window
- **THEN** it appears in Played recently even without Steam-owned playtime

#### Scenario: Window boundaries
- **WHEN** the latest supported play instant is at the start of thirteen days before today
- **THEN** the game is included, while an instant before that boundary or after the present instant is excluded

#### Scenario: Future evidence does not mask recent play
- **WHEN** a game has a future-dated observation as well as an older supported instant within the window
- **THEN** it remains in Played recently based on the older eligible evidence

#### Scenario: Date changes offline
- **WHEN** a date boundary moves a game's latest play outside the window while offline
- **THEN** it leaves Played recently and the count updates without network activity

#### Scenario: Only undated playtime exists
- **WHEN** a game has lifetime or manually entered minutes but no supported recent timestamp
- **THEN** it is not classified as played recently

### Requirement: Derived cards consistently explain their membership
Derived cards SHALL show a name, rule, cover treatment, and count from the same visible membership as their lists. Favorites and Played recently SHALL precede Quick wins, Never started, Almost done, Dropped, and Completed on Home and Collections. Existing rules, disclosures, read-only behavior, hide settings, empty suppression, and placement below custom collections SHALL remain.

#### Scenario: Card and contents agree
- **WHEN** a derived card is opened after a game is hidden or its membership changes
- **THEN** the card count and resulting list reflect the same visible derived membership

#### Scenario: Existing derived rules remain intact
- **WHEN** the revised cards are displayed
- **THEN** their existing thresholds and completion disclosures remain readable and membership is not editable
