## Purpose
Offer a local optional random choice among operable custom-collection members with accurate eligibility and sampled-pool disclosure.

## ADDED Requirements

### Requirement: Persistent eligibility follows collection and visibility policy
The eligible set SHALL contain distinct visible, operable custom-collection members except ordered-queue members explicitly marked done. Hidden/unavailable members SHALL follow global visibility policy. Achievement completion, playtime, length, recency, and popularity SHALL not exclude or weight candidates.

#### Scenario: Completed achievement set
- **WHEN** a visible member has all achievements unlocked but is not marked done in its queue
- **THEN** it remains eligible

#### Scenario: Hidden or done member
- **WHEN** a member is hidden/unavailable or explicitly done in an ordered queue
- **THEN** it is excluded without exposing hidden identity

#### Scenario: No app ranking
- **WHEN** eligible members have different lengths and recent play
- **THEN** those properties do not alter selection weights

### Requirement: Uniform selection applies to the actual per-spin pool
For a spin, the system SHALL remove the immediately previous result from the eligible set when alternatives exist and sample uniformly from the resulting pool. Every actual candidate SHALL have equal probability. The displayed chosen-from count SHALL equal the size actually sampled, with persistent exclusions and transient last-result exclusion separately disclosed.

#### Scenario: First spin
- **WHEN** three visible eligible games exist and no previous result exists
- **THEN** each has probability one third and the chosen-from count is three

#### Scenario: No-repeat re-spin
- **WHEN** three eligible games exist and the last result is one of them
- **THEN** the two other games each have probability one half, the prior result has zero, and the sampled count is two

#### Scenario: Two eligible games
- **WHEN** the player repeatedly spins a stable two-member collection
- **THEN** results alternate and each re-spin samples one candidate

#### Scenario: Previous result removed
- **WHEN** the previous result is no longer eligible
- **THEN** the current eligible set is sampled without excluding another member in its place

### Requirement: Roulette availability responds to current membership
Initial roulette entry SHALL require at least two eligible members on an operable custom overview. If eligibility shrinks during an open surface, re-spin SHALL be disabled when fewer than two remain. Stale results SHALL be invalidated on reveal and before navigation/commit; a still-valid result MAY remain inspectable.

#### Scenario: One remaining member
- **WHEN** the eligible set shrinks to one while a still-valid result is shown
- **THEN** detail remains available and re-spin is disabled

#### Scenario: Result becomes hidden or done
- **WHEN** the result loses eligibility before an action
- **THEN** stale navigation/commit is refused with understandable changed-collection feedback

#### Scenario: Empty eligible set
- **WHEN** every candidate is removed
- **THEN** the result is invalidated and no empty-pool sampling occurs

### Requirement: Picks are transient and selection is testable
The system SHALL retain the previous pick only for the current visit and SHALL store no pick history/preferences. Selection SHALL accept an injected random index source so its exact pool and no-repeat behavior can be deterministically verified.

#### Scenario: Visit ends
- **WHEN** the result is dismissed and the collection visit ends
- **THEN** the next visit has no previous-result exclusion

#### Scenario: Same random index
- **WHEN** the same pool/order and injected index are supplied
- **THEN** the same candidate is chosen without external state

### Requirement: A result changes a collection only through explicit queue commitment
A result SHALL identify its game and offer detail. Only an ordered queue SHALL offer an explicit Move next action. It SHALL use current validated membership, preserve done positions and other-member relative order, and commit through normal collection mutation. Spinning/opening/dismissing SHALL not mutate collection state.

#### Scenario: Queue commitment
- **WHEN** the player commits Move next for a still-eligible queue result
- **THEN** it moves to the first not-done slot, preserving other ordering and done positions

#### Scenario: Concurrent queue change
- **WHEN** the collection changes before commitment
- **THEN** the action revalidates current state and does not overwrite it with a stale sequence

#### Scenario: Non-queue result
- **WHEN** roulette chooses from a basic/deadline list
- **THEN** no queue-ordering action is offered

### Requirement: Roulette does not require motion or haptic feedback
The result SHALL be understandable without motion, reveal directly under reduced motion, and remain accessible with labelled actions. Spins/reveals SHALL be silent. Only a successful explicit queue mutation SHALL use the existing committed-success feedback.

#### Scenario: Reduced motion
- **WHEN** the player has reduced motion enabled
- **THEN** the selected result appears directly with full content/actions

#### Scenario: Spin without commit
- **WHEN** a random result is revealed
- **THEN** no haptic, network request, or persisted selection is produced
