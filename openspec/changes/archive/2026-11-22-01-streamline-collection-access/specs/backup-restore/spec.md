## ADDED Requirements

### Requirement: Favorite preferences survive compatible backup merges
Manual backups and snapshots SHALL include favorite preferences keyed by app ID, including explicit clears and absent-game records. Import SHALL merge explicit favorites without changing Focus or collection memberships. Omitted preferences SHALL preserve local values and leave new games unfavorited. Preflight validation, atomic merge, and warned cross-account import SHALL apply. Changing accounts remains a separate reset.

#### Scenario: Set and cleared favorites round-trip
- **WHEN** a backup with explicit true and false favorite values is exported and restored
- **THEN** those values are restored exactly without changing Focus or custom membership

#### Scenario: Legacy import preserves a local favorite
- **WHEN** a supported older backup omits favorite preferences
- **THEN** existing favorite preferences are preserved and absent values do not become an instruction to clear them

#### Scenario: Invalid preference payload
- **WHEN** a backup contains invalid or duplicate contradictory preference records
- **THEN** import fails validation before any write

#### Scenario: Cross-account merge
- **WHEN** the user confirms the existing Steam identity mismatch warning
- **THEN** explicit imported favorite values follow the same per-app-ID merge semantics without changing the configured account
