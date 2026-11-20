## ADDED Requirements

### Requirement: Detail can explicitly refresh one game's achievements
The system SHALL offer a named Refresh achievements action for the selected tracked app ID, independent of player-count refresh. It SHALL reuse the common coordinated achievement fetch/merge rules with bounded repeated triggers and no playtime poll or whole-library sync. It SHALL publish pending, updated, no-change, unusable/private/no-stats, and transport-failure outcomes; unsupported/private responses SHALL NOT be treated as proof that a game has no achievements. Failed or unusable responses SHALL retain last-good data. Successful changes SHALL update dependent local progression through its existing recompute rules without replacing frozen earned rarity.

#### Scenario: Successful changed response
- **WHEN** a user-triggered refresh returns usable changed achievement data
- **THEN** the merged data and derived local progression update and the action reports updated

#### Scenario: Success without changes
- **WHEN** usable achievement content is unchanged by the refresh
- **THEN** the action reports no change rather than implying new unlocks or a failed request

#### Scenario: Private or unavailable data
- **WHEN** Steam provides no usable player stats or the request fails
- **THEN** detail reports the corresponding outcome with a retry path and retains the prior achievement set

#### Scenario: Concurrent request sources
- **WHEN** detail, sync, or an active monitor request the same account/game achievements together
- **THEN** they use the shared serialization/coalescing contract and stale data cannot overwrite a newer observation

### Requirement: Detail achievement refresh belongs to its initiating account and game
A refresh SHALL capture the configured account and selected game before fetching and validate that ownership at the commit boundary. An account-change reset, pending account transition, changed identity, hidden game, or removed shared game SHALL prevent stale work from merging or publishing a result to a different detail/account. Missing credentials SHALL produce an actionable unavailable state without a request. Same-account key rotation SHALL NOT be mistaken for a different player's data.

#### Scenario: Account changes during fetch
- **WHEN** an account transition starts before an old account's achievement response commits
- **THEN** the old response is discarded without writing into the new account's database

#### Scenario: Detail switches games
- **WHEN** detail switches to another game while a refresh is pending
- **THEN** the earlier game's result is not displayed as the new game's outcome

#### Scenario: Game is hidden during fetch
- **WHEN** the refreshed game becomes hidden or is explicitly removed before commit
- **THEN** the action does not restore its visibility or write a stale detail outcome
