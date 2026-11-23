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
The archive SHALL freeze collection identity/name, goal, persisted cutoff membership, estimate basis, deadline, finalization time, and per-member evidence/metrics. Timestamped membership and visibility provenance SHALL establish cutoff state. Goal evaluation SHALL use only persisted members visible at cutoff, consistent with hidden-games, and distinguish estimated met, not met, and undetermined.

#### Scenario: Known goal reached
- **WHEN** a supported deadline snapshot or dated evidence shows every member in a non-empty cutoff goal-evaluation membership met its selected estimate
- **THEN** the archive records estimated goal met under that HLTB basis

#### Scenario: Known shortfall
- **WHEN** supported cutoff evidence shows a positive shortfall for at least one member in the cutoff goal-evaluation membership, even if other evaluated members have unknown estimates
- **THEN** the archive records estimated goal not met and preserves the known shortfall

#### Scenario: Evidence is insufficient
- **WHEN** the estimates, membership, visibility, or dated playtime needed to evaluate the goal cannot be established
- **THEN** the archive records undetermined, explains the missing evidence, and keeps available metrics

#### Scenario: Member is hidden after the cutoff
- **WHEN** two visible members are present at cutoff, one has a supported shortfall, and that member is hidden before finalization
- **THEN** the frozen evaluation retains both cutoff members and records estimated goal not met with the known shortfall, regardless of the current filtered collection read
- **AND** the hidden member's identity is not shown or navigable, though its frozen evidence may remain as a non-navigable archive entry

#### Scenario: Member is removed after the cutoff
- **WHEN** a member is present and visible at cutoff and is removed before finalization
- **THEN** the frozen evaluation retains that member and its cutoff evidence

#### Scenario: Member is hidden at the cutoff
- **WHEN** a persisted member is hidden at cutoff
- **THEN** its hidden-at-cutoff provenance is retained and it is excluded from the goal-evaluation membership, regardless of whether it is later unhidden

#### Scenario: Every persisted member is hidden at the cutoff
- **WHEN** persisted cutoff membership is established but no member was visible at cutoff
- **THEN** the archive records undetermined with the empty goal-evaluation membership as the reason, never estimated goal met

#### Scenario: Empty membership at cutoff
- **WHEN** a new, legacy, or restored active collection is positively established to have no persisted members at the cutoff
- **THEN** the archive records undetermined with the empty membership as the reason, never estimated goal met

#### Scenario: Every selected estimate is unknown
- **WHEN** a non-empty cutoff goal-evaluation membership is established but no member has a supported selected estimate
- **THEN** the archive records undetermined and does not treat the members as having met their estimates

#### Scenario: Mixed known and unknown estimates
- **WHEN** a non-empty cutoff goal-evaluation membership has supported evidence that known-estimate members met their estimates, but another evaluated member's selected estimate is unknown and no positive shortfall is established
- **THEN** the archive records undetermined rather than estimated goal met

#### Scenario: Archived metrics are inspected
- **WHEN** the player reads a frozen member result
- **THEN** supported credited minutes, selected estimate, remaining minutes, session counts, and trophy counts are identified as cutoff evidence, with unavailable values kept distinct from zero

#### Scenario: Later changes arrive
- **WHEN** post-deadline play or a later sync changes estimates or corrects placement after archiving
- **THEN** the archived outcome and original measurements remain unchanged

#### Scenario: Unhiding a post-cutoff hide after archive
- **WHEN** a member visible at cutoff is hidden before archival and unhidden after the collection is archived
- **THEN** its frozen evaluated membership, evidence, and outcome remain unchanged after it becomes visible again
