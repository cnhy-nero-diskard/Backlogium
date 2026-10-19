## ADDED Requirements

### Requirement: Recovery epoch validity is checked before import mutation
Version 3 import preflight SHALL reject every recovery observation or funded contribution whose baseline epoch is zero or negative, before any database or derived-state mutation. A file reported valid SHALL satisfy the recovery writer's structural epoch constraints rather than defer those checks until persistence or a subsequent Steam observation. Rejection SHALL identify the invalid record and leave existing baselines, history, raw daily credit and progression unchanged. This requirement SHALL preserve supported positive-epoch v3 round trips and legacy v1/v2 import behavior; missing recovery epochs in legacy formats SHALL NOT be interpreted as an explicit zero epoch or as new lifetime credit.

#### Scenario: Zero epoch on an observation without funded history
- **WHEN** a version 3 backup contains a recovery observation with epoch zero, including a file with no funded contributions
- **THEN** preflight rejects that observation before it can become an active baseline and no receiving state changes

#### Scenario: Zero or negative contribution epoch
- **WHEN** a version 3 contribution uses epoch zero or a negative epoch even though its placement quantities otherwise conserve
- **THEN** preflight rejects the contribution before persistence begins and reports the epoch problem

#### Scenario: Negative observation epoch
- **WHEN** a version 3 observation has a negative baseline epoch
- **THEN** the file is rejected without changing stored or derived activity

#### Scenario: A supported positive epoch is restored then read
- **WHEN** a coherent same-account version 3 backup with a positive epoch is restored and the next trustworthy Steam reading reports the same cumulative total
- **THEN** the restored epoch and quantity remain coherent and no already-funded minutes become a new increase

#### Scenario: Legacy import keeps existing compatibility
- **WHEN** a supported version 1 or 2 backup without recovery epoch fields is imported
- **THEN** its existing merge and fresh-baseline rules apply without rejecting it for missing modern fields or re-crediting its history

#### Scenario: Rejected recovery data leaves unrelated history intact
- **WHEN** a backup contains otherwise importable games, sessions and daily records together with an invalid recovery epoch
- **THEN** none of those records or the receiving progression state is modified

### Requirement: V3 recovery graphs are representable and linked to their source baseline
Version 3 recovery observations and contribution ranges SHALL stay within the nonnegative `Int` playtime domain of the live Steam observation writer; preflight SHALL reject values outside `0..Int.MAX_VALUE` rather than allow restore to clamp the detector pair. Every funded contribution SHALL have one observation for the same source account and app. Recovery epochs may differ across independently merged backup lineages: when a contribution epoch is equal to or newer than the retained observation epoch, its endpoint SHALL be covered by that observation's high-water; an older-epoch contribution SHALL remain valid historical quantity after a deliberate baseline re-establishment, even when its cumulative endpoint exceeds the newer epoch's lower high-water. Recovery observations and contributions SHALL use the SteamID64 recorded by the backup identity; this source-identity check SHALL NOT block importing a foreign backup whose recovery records consistently identify that foreign source account. Timed placements, allocations, recipes and replacements SHALL remain linked to their contribution's account/app identity, and portable timed-session links SHALL resolve unambiguously.

#### Scenario: Unrepresentable Steam detector values
- **WHEN** a version 3 observation high-water or contribution endpoint exceeds `Int.MAX_VALUE`
- **THEN** full-file preflight rejects the specific recovery record before any mutation instead of importing a value the live detector cannot represent

#### Scenario: Funded range is not covered by its observation
- **WHEN** a contribution has no same-account/app observation, or its endpoint exceeds the observation high-water while it belongs to the same or a newer epoch
- **THEN** preflight rejects that contribution as incoherent before persistence

#### Scenario: Older epoch history survives baseline re-establishment
- **WHEN** a contribution from an older positive epoch is present beside a newer observation epoch with a lower high-water
- **THEN** the historical contribution remains valid and conserved without being clamped to the newer baseline

#### Scenario: Cross-account import preserves source attribution
- **WHEN** the user confirms importing a foreign backup whose recovery accounts match its recorded identity
- **THEN** validation permits the file, the existing warning/history merge policy remains, and the foreign observations remain inactive for the receiving account
- **WHEN** recovery records inside one file identify a different account from that file's identity
- **THEN** preflight rejects the inconsistent source-account link before mutation

#### Scenario: Ambiguous portable timed-session reference
- **WHEN** a timed placement's natural key matches more than one exported session
- **THEN** preflight rejects the ambiguous recovery reference before persistence
