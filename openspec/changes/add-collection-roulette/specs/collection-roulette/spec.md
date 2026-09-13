## ADDED Requirements

### Requirement: The candidate pool is the collection's own members
The system SHALL draw a roulette result only from the members of the collection it was invoked from.
A member SHALL be excluded only where the collection itself has marked it done — an ordered-queue
member the player marked complete. No member SHALL be excluded on the basis of a property the app
judges, including achievement completion, playtime, completion length, or recency of play.

#### Scenario: Members are the pool
- **WHEN** a roulette is spun for a collection
- **THEN** the result is one of that collection's members

#### Scenario: A queue member marked done is excluded
- **WHEN** an ordered-queue collection has members the player marked done
- **THEN** those members cannot be selected

#### Scenario: A fully-completed game remains eligible
- **WHEN** a member has every achievement unlocked
- **THEN** it remains in the pool, because achievement completion is not a statement that the player
  is finished with the game

#### Scenario: No app-side judgment
- **WHEN** members differ in playtime, completion length, or how recently they were played
- **THEN** none of those differences affects whether a member is in the pool

### Requirement: Selection is uniform and disclosed as uniform
The system SHALL select uniformly at random among eligible members, giving no member a greater or
lesser chance than any other, and SHALL convey that the selection is uniform rather than a
recommendation.

#### Scenario: Every eligible member is equally likely
- **WHEN** a roulette is spun
- **THEN** each eligible member has the same probability of being selected

#### Scenario: Selection is not a recommendation
- **WHEN** a result is presented
- **THEN** the surface conveys that the choice was arbitrary rather than reasoned

#### Scenario: No property influences the outcome
- **WHEN** one eligible member is far shorter, far less played, or far closer to completion than
  another
- **THEN** neither is more likely to be selected

### Requirement: The pool is stated, and exclusions are explained
The system SHALL state how many members the result was chosen from. Where that is fewer than the
collection's total membership, the system SHALL convey why the remainder were excluded.

#### Scenario: Pool size stated
- **WHEN** a result is presented
- **THEN** the number of members it was chosen from is stated

#### Scenario: Exclusions explained
- **WHEN** the eligible pool is smaller than the collection's membership
- **THEN** the reason those members are not eligible is conveyed

#### Scenario: No exclusions
- **WHEN** every member is eligible
- **THEN** the pool size is stated and no exclusion explanation is presented

### Requirement: Re-spinning does not repeat the previous result
Where at least one eligible member other than the immediately previous result exists, the system
SHALL exclude that previous result from the next spin. Where the previous result is the only
eligible member, it SHALL be returned again.

#### Scenario: A different game on re-spin
- **WHEN** the player spins again and more than one eligible member exists
- **THEN** the result differs from the immediately previous result

#### Scenario: Two-member collection alternates
- **WHEN** a collection has exactly two eligible members and the player spins repeatedly
- **THEN** the results alternate between them

#### Scenario: Exclusion applies only to the immediately previous result
- **WHEN** the player spins three times
- **THEN** the third spin may return the first spin's result

#### Scenario: Only one eligible member remains
- **WHEN** the previous result is the only eligible member
- **THEN** it is returned rather than the spin failing or offering nothing

### Requirement: The roulette records nothing
The system SHALL NOT persist any roulette outcome, count, or history. Knowledge of the immediately
previous result SHALL last only for the current visit to the collection and SHALL NOT survive leaving
the collection or the app process ending.

#### Scenario: No stored outcome
- **WHEN** a result is produced
- **THEN** nothing is written to storage as a consequence

#### Scenario: Previous result forgotten on leaving
- **WHEN** the player leaves the collection and returns
- **THEN** every eligible member is available to the next spin, including the last result seen

#### Scenario: No pick history
- **WHEN** the roulette has been used repeatedly
- **THEN** no surface presents how often a game has been picked or when it was last picked

### Requirement: A result offers a next step
The system SHALL present the selected game's identity and SHALL offer to open that game. Where the
collection carries a sequence the result can act on, the system SHALL additionally offer one
committing action against that sequence. The system SHALL NOT present a control for an action it
cannot perform.

#### Scenario: Result identifies the game
- **WHEN** a result is presented
- **THEN** it names the selected game and presents its art

#### Scenario: Opening the game
- **WHEN** the player chooses to open the selected game
- **THEN** that game's detail is presented

#### Scenario: Committing against an ordered queue
- **WHEN** the collection is an ordered queue and the player commits the result to it
- **THEN** the selected game becomes the queue's next game, the sequence is persisted, and the
  committed-action success feedback is delivered

#### Scenario: No sequence to act on
- **WHEN** the collection carries no sequence
- **THEN** only opening the game is offered, and no disabled or placeholder action is presented

#### Scenario: Opening commits nothing
- **WHEN** the player opens the selected game
- **THEN** no collection state changes and no committed-action feedback is delivered

### Requirement: The roulette is offered only where it is a choice
The system SHALL offer the roulette only where a collection has at least two eligible members.

#### Scenario: Enough members
- **WHEN** a collection has two or more eligible members
- **THEN** the roulette is offered

#### Scenario: One eligible member
- **WHEN** a collection has exactly one eligible member
- **THEN** the roulette is not offered, since there is nothing to choose between

#### Scenario: No eligible members
- **WHEN** every member of a collection is excluded, or the collection is empty
- **THEN** the roulette is not offered

#### Scenario: Availability follows membership
- **WHEN** a collection's membership or done-marks change such that it crosses the two-member
  threshold
- **THEN** the roulette becomes offered or stops being offered accordingly

### Requirement: The spin is decoration and the result does not depend on it
The system SHALL present the result fully without its animation. Where the platform indicates that
motion should be reduced, the system SHALL reveal the result directly rather than playing a
shortened or substituted animation. The animation SHALL NOT use the accent reserved for milestone
moments.

#### Scenario: Reduced motion
- **WHEN** the platform indicates animations should be reduced or disabled
- **THEN** the result is revealed directly with no spin

#### Scenario: Result legible without motion
- **WHEN** the result is revealed without animation
- **THEN** the selected game, the pool size, and the offered actions are all present and legible

#### Scenario: Milestone accent not diluted
- **WHEN** the spin is presented
- **THEN** it does not use the accent reserved for level-up, streak-milestone, and completion moments

#### Scenario: Spinning is silent
- **WHEN** a spin runs and a result is revealed
- **THEN** no haptic feedback is delivered, since nothing has been committed

### Requirement: Selection is deterministic given its randomness source
The selection rule SHALL take its source of randomness as an input rather than reading a global one,
so that a given source and pool produce a defined result.

#### Scenario: Reproducible selection
- **WHEN** the same pool and the same randomness source are supplied
- **THEN** the same member is selected

#### Scenario: No-repeat rule is exercisable
- **WHEN** a previous result is supplied along with a pool
- **THEN** the outcome can be checked to exclude that previous result without relying on repeated
  sampling
