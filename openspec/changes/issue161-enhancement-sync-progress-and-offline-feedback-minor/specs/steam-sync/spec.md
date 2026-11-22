# Steam Sync Delta

## ADDED Requirements

### Requirement: Steam and achievement operations report measured progress
Each observable Steam sync and achievement refresh operation SHALL expose its current phase and operation identity. It SHALL report processed items and a total where work is enumerated, and an indeterminate phase otherwise.

#### Scenario: Enumerable achievement work
- **WHEN** the operation has selected the games it will process in a phase
- **THEN** progress reflects completed attempts against the selected set, including unsuccessful or skipped items

#### Scenario: Unknown duration or count
- **WHEN** the operation is fetching a library list or waiting for a response with no known item total
- **THEN** progress states the phase without a synthetic percentage

#### Scenario: Operation changes
- **WHEN** an account changes or a later attempt starts
- **THEN** counts and results from the earlier operation are not displayed for the current operation

### Requirement: Progress outcomes describe the actual operation
An operation SHALL distinguish queued, constraint wait, running, retry backoff, cancelled, succeeded, partially succeeded, and failed when the execution state supports those distinctions. Terminal summaries SHALL report available processed, updated, skipped, and failed counts without treating successful fetches as changes.

#### Scenario: Mixed results
- **WHEN** a refresh completes with both successful and failed item processing
- **THEN** the view identifies partial success and retains the available result counts

#### Scenario: Unknown scheduling cause
- **WHEN** the operation is queued and a specific blocker is unavailable
- **THEN** the status remains queued rather than claiming network wait or retry backoff
