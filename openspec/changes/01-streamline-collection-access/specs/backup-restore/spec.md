## ADDED Requirements

### Requirement: Favorite preferences survive compatible backup merges
Manual backups and automatic snapshots SHALL carry app-owned favorite preferences keyed by Steam app ID, including explicit false values and retained preferences for temporarily absent games. Import SHALL restore explicit values without changing Focus or duplicating membership. Older backups that omit favorite preferences SHALL leave existing preferences unchanged and give newly introduced games the default unfavorited state. Preference data SHALL follow the existing validation, all-or-nothing import, and warned cross-account merge rules; configured-account changes remain a separate reset operation.

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
