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
