## ADDED Requirements

### Requirement: Imported history remains an undated XP base
Frozen historical import credit SHALL remain undated in the daily attribution projection. It SHALL use the existing engine/taper and current stored import state; consumed or re-filed credit SHALL NOT be duplicated. The UI SHALL expose its role in the undated remainder without estimating play dates.

#### Scenario: Steam history imported
- **WHEN** imported minutes contribute to current total XP
- **THEN** their base contribution appears separately instead of on install/first-sync/oldest-unlock date

#### Scenario: Import reset or credit re-filed
- **WHEN** the stored backfill amount changes through an existing supported workflow
- **THEN** the attribution projection updates from the new state and preserves total reconciliation
