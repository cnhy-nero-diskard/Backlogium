## ADDED Requirements

### Requirement: Detail can explicitly refresh one game's achievements
Detail SHALL offer a named per-game achievement refresh independent of player count, using the shared fetch and merge path with bounded repeats. It SHALL report pending, updated, unchanged, unusable, or failed and retain last-good data on failure. It SHALL preserve frozen rarity and use durable recompute-only work when XP inputs change, without watch delivery.

#### Scenario: Successful changed response
- **WHEN** a user-triggered refresh returns usable changed achievement data
- **THEN** the merged data and derived local progression update and the action reports updated

#### Scenario: Success without changes
- **WHEN** usable achievement content is unchanged by the refresh
- **THEN** the action reports no change rather than implying new unlocks or a failed request

#### Scenario: Private or unavailable data
- **WHEN** Steam provides no usable player stats or the request fails
- **THEN** detail reports the corresponding outcome with a retry path and retains the prior achievement set
- **AND** it does not infer that the game has no achievements

#### Scenario: Progress recompute is interrupted
- **WHEN** changed achievement data commits and the app stops before derived progress is recomputed
- **THEN** the existing recovery path resumes the recompute without delivering an achievement-watch notification

#### Scenario: Concurrent request sources
- **WHEN** detail, sync, or an active monitor request the same account/game achievements together
- **THEN** they use the shared serialization/coalescing contract and stale data cannot overwrite a newer observation
- **AND** manual refresh can coexist with automatic catalogue hydration without requesting playtime or starting a library sweep

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
