## ADDED Requirements

### Requirement: Inline HowLongToBeat match selection
When a single game's HowLongToBeat lookup yields ambiguous candidates, the system SHALL let the user
choose among those candidates from the library itself, without navigating to a separate screen.

#### Scenario: Choosing a candidate in place
- **WHEN** a single-game lookup started from the game's menu reports an ambiguous match
- **THEN** the candidates are presented for selection without navigating to a separate screen

#### Scenario: Selection resolves immediately
- **WHEN** the user selects a candidate
- **THEN** the match is resolved, the game's status reflects the resolution, and no separate
  confirmation step is required

#### Scenario: Changing an already-resolved match
- **WHEN** a game's HowLongToBeat match is already resolved
- **THEN** changing the match is offered, and choosing it presents candidates to select from

#### Scenario: An offered change is abandoned
- **WHEN** the user asks to change a resolved match and then dismisses the picker without selecting
- **THEN** the previously resolved match remains in effect, unchanged

#### Scenario: Lookup in flight
- **WHEN** a lookup is running for the picker
- **THEN** the picker reflects the in-flight state and the selection action is unavailable until it
  completes

#### Scenario: Lookup finds a single confident match
- **WHEN** a single-game lookup resolves confidently on its own
- **THEN** no candidate selection is presented and the resolved match is reported

#### Scenario: Many candidates
- **WHEN** more candidates are available than fit on screen
- **THEN** the candidate list scrolls within the picker rather than overflowing it, and every
  candidate is reachable

### Requirement: Candidate cover art
The system SHALL present cover art alongside each HowLongToBeat candidate, wherever candidates are
shown, so visually similar titles can be distinguished.

#### Scenario: Art shown for candidates
- **WHEN** candidates are presented for selection
- **THEN** each candidate shows its cover art alongside its name and completion length

#### Scenario: Art unavailable
- **WHEN** a candidate has no stored image, or the image fails to load
- **THEN** a themed placeholder is shown in its place and the candidate remains selectable

## MODIFIED Requirements

### Requirement: HLTB match review
The system SHALL provide a surface listing games flagged as needing an HLTB match, and SHALL let the
user open a flagged game and select the correct HowLongToBeat entry from its candidates. This surface
serves the batch case; the entry point to it SHALL be presented only when at least one game is
flagged as needing review.

#### Scenario: Reviewing flagged games
- **WHEN** the user opens the match-review surface and games are flagged as needing review
- **THEN** each flagged game is listed with its candidate HowLongToBeat entries available for selection

#### Scenario: Confirming a match
- **WHEN** the user selects the correct candidate for a flagged game
- **THEN** the game is marked resolved, its completion length becomes available to the goal and gamification features, and it is removed from the review list

#### Scenario: No games need review
- **WHEN** the user opens the match-review surface and no games are flagged
- **THEN** the surface indicates there is nothing to review

#### Scenario: Entry point hidden when nothing is flagged
- **WHEN** no games are flagged as needing review
- **THEN** no entry point to the match-review surface is presented

#### Scenario: Entry point shown with a count
- **WHEN** one or more games are flagged as needing review
- **THEN** the entry point is presented and indicates how many games are awaiting review
