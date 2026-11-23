# Custom Collections Delta

## ADDED Requirements

### Requirement: Deadline collections can be finalized exactly once
A custom deadline collection SHALL be eligible to archive after its target local day ends. The explicit finalization SHALL atomically store one immutable outcome while removing the collection from active use.

#### Scenario: Concurrent edits or finalization
- **WHEN** a preview races with a deadline or membership edit, or two requests try to archive
- **THEN** a stale preview must be refreshed and an already archived collection returns its existing outcome

#### Scenario: Unarchived collection is edited
- **WHEN** the deadline changes before the user archives the collection
- **THEN** the current confirmed deadline governs eligibility under the existing editing contract

### Requirement: Archived outcome uses evidence at the deadline
The archive SHALL retain identity, name, members and goal as of the cutoff where supported, selected HLTB basis, deadline, finalization time, per-member evidence and final metrics. Outcome SHALL distinguish estimated goal met, estimated goal not met, and undetermined.

Estimated goal met SHALL require established, non-empty cutoff membership and supported evidence that every member met its selected estimate; empty membership or incomplete required evidence SHALL NOT count as success.

#### Scenario: Known goal reached
- **WHEN** a supported deadline snapshot or dated evidence shows every member in a non-empty cutoff membership met its selected estimate
- **THEN** the archive records estimated goal met under that HLTB basis

#### Scenario: Known shortfall
- **WHEN** supported cutoff evidence shows a positive shortfall for at least one member, even if other members have unknown estimates
- **THEN** the archive records estimated goal not met and preserves the known shortfall

#### Scenario: Evidence is insufficient
- **WHEN** the estimates, membership, or dated playtime needed to evaluate the goal cannot be established
- **THEN** the archive records undetermined, explains the missing evidence, and keeps available metrics

#### Scenario: Empty membership at cutoff
- **WHEN** a new, legacy, or restored active collection is positively established to have no members at the cutoff
- **THEN** the archive records undetermined with the empty membership as the reason, never estimated goal met

#### Scenario: Every selected estimate is unknown
- **WHEN** a non-empty cutoff membership is established but no member has a supported selected estimate
- **THEN** the archive records undetermined and does not treat the members as having met their estimates

#### Scenario: Mixed known and unknown estimates
- **WHEN** a non-empty cutoff membership has supported evidence that known-estimate members met their estimates, but another member's selected estimate is unknown and no positive shortfall is established
- **THEN** the archive records undetermined rather than estimated goal met

#### Scenario: Archived metrics are inspected
- **WHEN** the player reads a frozen member result
- **THEN** supported credited minutes, selected estimate, remaining minutes, session counts, and trophy counts are identified as cutoff evidence, with unavailable values kept distinct from zero

#### Scenario: Later changes arrive
- **WHEN** post-deadline play or a later sync changes estimates or corrects placement after archiving
- **THEN** the archived outcome and original measurements remain unchanged
