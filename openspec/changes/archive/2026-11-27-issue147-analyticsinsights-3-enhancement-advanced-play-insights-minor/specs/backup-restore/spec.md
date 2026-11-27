# Backup-Restore Delta

## ADDED Requirements

### Requirement: Backup completion observations carry validated source identity
New backups SHALL include every persisted completion observation in an `achievementCompletions` section; when none exist, the section MAY be absent or empty. Each observation SHALL carry the backup identity's SteamID64, a positive app ID in the game list, and a plausible ISO-8601 timestamp. Export SHALL reject credential/profile identity mismatches and completion evidence without a Steam identity. Older supported backups may omit the section; absence or emptiness SHALL NOT erase local evidence.

#### Scenario: Legacy backup omits completion observations
- **WHEN** a supported older backup has no `achievementCompletions` section
- **THEN** it remains importable and existing local completion evidence is unchanged

#### Scenario: Completion evidence is validated with the backup identity
- **WHEN** a backup contains a completion with a blank or mismatched account, invalid or implausible timestamp, invalid app ID, absent game, or duplicate app ID
- **THEN** full-file preflight rejects the backup before any database write

#### Scenario: Export identity changes before its Room snapshot
- **WHEN** credentials were captured for one nonblank account but the profile in the Room snapshot belongs to another nonblank account
- **THEN** export is rejected and completion evidence is neither mislabeled nor silently dropped

#### Scenario: Completion evidence has no source identity
- **WHEN** persisted completion evidence exists but neither credentials nor the Room profile identifies an account
- **THEN** export is rejected rather than discarding the evidence

### Requirement: Backup completion observations merge only for their source account
On import, completion observations SHALL merge by account and app ID, retaining the earliest observation for same-account imports. A warned cross-account import SHALL continue to merge ordinary history under the existing policy but SHALL NOT attach foreign completion observations to the configured account, because completion observes one player's confirmed achievement-set transition rather than a portable historical fact about the receiving player.

#### Scenario: Same-account completion observations merge
- **WHEN** a same-account backup contains an observation earlier than the locally stored completion for the same app
- **THEN** the earlier observation is retained, independent of import order, and later or absent evidence does not replace it

#### Scenario: Cross-account import excludes completion observations only
- **WHEN** the user confirms the existing warning for a foreign backup containing completion observations
- **THEN** ordinary history follows the warned cross-account merge policy while the foreign completion observations are not attached to the configured account
