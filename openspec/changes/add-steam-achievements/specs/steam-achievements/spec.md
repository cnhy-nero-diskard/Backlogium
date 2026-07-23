## ADDED Requirements

### Requirement: Fetch Steam achievement data
The system SHALL fetch, from Steam, each in-scope game's per-player achievement unlock
state and the global unlock percentage for each of that game's achievements, and MAY fetch
the game's achievement schema for display names and icons.

#### Scenario: Fetching a game's achievements
- **WHEN** an in-scope game's achievement data is stale or missing
- **THEN** the system requests that game's per-player unlock state and global unlock
  percentages from Steam and stores the results

#### Scenario: Game has no achievements
- **WHEN** a fetched game exposes no achievements
- **THEN** the system records that the game has no achievements and does not treat it as an error

#### Scenario: Achievement fetch fails for a game
- **WHEN** a game's achievement request fails (private profile, no stats, or a transport error)
- **THEN** that game's achievement fetch is skipped without failing the overall sync, and any
  previously stored achievement data for that game is left intact

### Requirement: Scoped and freshness-gated achievement sync
The system SHALL limit achievement fetching to games the player engages with — those with
tracked play sessions and goal-tagged games — and SHALL refetch a game's achievements only
when its stored data is older than a freshness threshold or absent.

#### Scenario: In-scope game is refreshed when stale
- **WHEN** a played or goal game's stored achievement data is older than the freshness threshold
- **THEN** its achievements are refetched on the next sync

#### Scenario: Out-of-scope game is not fetched
- **WHEN** a game has no tracked sessions and is not a goal game
- **THEN** its achievements are not fetched during a routine sync

#### Scenario: Fresh in-scope game is not refetched
- **WHEN** an in-scope game's stored achievement data is within the freshness threshold
- **THEN** it is not refetched on that sync

### Requirement: Persist achievements with a first-unlock rarity snapshot
The system SHALL persist each achievement keyed by its game and achievement id, storing its
unlock state, unlock time when available, the current global unlock percentage, and a rarity
percentage snapshotted at the first sync that observes the achievement as unlocked with a
known global percentage. The snapshot SHALL NOT change on later syncs.

#### Scenario: Snapshot taken at first observed unlock
- **WHEN** a sync first observes an achievement as unlocked and a global unlock percentage is available
- **THEN** the system stores that percentage as the achievement's rarity snapshot

#### Scenario: Snapshot is stable against later drift
- **WHEN** a later sync reports a different global unlock percentage for an already-snapshotted achievement
- **THEN** the stored rarity snapshot is unchanged, while the current global percentage is updated for display

#### Scenario: Still-locked achievement has no snapshot
- **WHEN** an achievement has never been observed unlocked
- **THEN** it has no rarity snapshot

### Requirement: Feed achievement XP into gamification
The system SHALL build the gamification engine's achievement inputs from stored
achievements — using the rarity snapshot as each achievement's rarity input — and pass them
into the XP recompute so unlocked achievements contribute XP to the player's total.

#### Scenario: Unlocked achievements contribute XP
- **WHEN** the gamification values are recomputed and the player has unlocked achievements with a rarity snapshot
- **THEN** the recompute includes those achievements so their tiered XP is added to the player's total XP

#### Scenario: Locked or un-snapshotted achievements contribute nothing
- **WHEN** an achievement is locked, or unlocked but without a rarity snapshot
- **THEN** it contributes zero XP to the recompute

#### Scenario: XP uses the snapshot, not the live percentage
- **WHEN** an achievement's current global percentage differs from its rarity snapshot
- **THEN** its XP contribution is computed from the snapshot, so already-earned XP does not change
