## ADDED Requirements

### Requirement: Collection overview offers a roulette
The collection overview SHALL offer a roulette action where the collection has at least two eligible
members, presented as an action on the overview rather than inside the management form, since it acts
on the collection rather than configuring it. The action SHALL NOT appear on Home's collection cards.

#### Scenario: Action present on the overview
- **WHEN** a collection with two or more eligible members is opened
- **THEN** the overview offers the roulette

#### Scenario: Action absent where it is not a choice
- **WHEN** a collection has fewer than two eligible members
- **THEN** the overview does not offer the roulette

#### Scenario: Not in the management form
- **WHEN** the collection management form is open
- **THEN** the roulette is not offered there

#### Scenario: Not on Home
- **WHEN** Home's collections section is shown
- **THEN** no collection card offers the roulette

#### Scenario: Overview otherwise unchanged
- **WHEN** the roulette action is present
- **THEN** the overview's members, metrics, pacing, and customization action are unchanged

### Requirement: The roulette result is presented as a decision
The result presentation SHALL name the selected game, present its art, state how many members it was
chosen from, and offer to open that game and to spin again. Where the collection is an ordered queue,
it SHALL additionally offer to make the selected game the queue's next game.

The result SHALL use the collection's stored accent rather than the milestone accent, so it reads as
belonging to that collection and does not claim a milestone.

#### Scenario: Result content
- **WHEN** a result is presented
- **THEN** it shows the game's name and art, the pool size it was chosen from, and actions to open
  the game and spin again

#### Scenario: Queue commit offered
- **WHEN** the collection is an ordered queue
- **THEN** the result additionally offers to make the selected game the queue's next game

#### Scenario: Queue commit applied
- **WHEN** the player commits the result to an ordered queue
- **THEN** the sequence is persisted with that game next, the next-game surface reflects it, and the
  success feedback is delivered once

#### Scenario: Accent belongs to the collection
- **WHEN** a result is presented
- **THEN** it is tinted with the collection's stored accent and does not use the milestone accent

#### Scenario: Dismissing the result
- **WHEN** the player dismisses the result without acting on it
- **THEN** nothing is changed, nothing is recorded, and no feedback is delivered

#### Scenario: Result reachable by an accessibility service
- **WHEN** a result is presented
- **THEN** the selected game, the pool size, and each offered action are announced
