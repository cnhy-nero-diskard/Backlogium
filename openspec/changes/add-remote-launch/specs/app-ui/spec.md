## ADDED Requirements

### Requirement: Play on Desktop action
Game detail SHALL offer an action to start the game on the paired desktop, present only where an
agent is paired, currently reachable, and reporting the game as installed. The action SHALL show a
pending state from acceptance until the launch resolves, and SHALL state its outcome plainly.

#### Scenario: Action offered
- **WHEN** an agent is paired and reachable and reports the game installed
- **THEN** game detail offers to start the game on that desktop

#### Scenario: Game not installed
- **WHEN** the paired desktop does not report the game installed
- **THEN** the action is not offered, and game detail states that the game is not installed there

#### Scenario: Desktop unreachable
- **WHEN** the paired desktop cannot be reached
- **THEN** the action is not offered, and game detail states that the desktop is unreachable

#### Scenario: No agent paired
- **WHEN** no agent is paired
- **THEN** no launch action appears, and game detail is presented exactly as it is without the
  feature

#### Scenario: Pending state
- **WHEN** a launch has been accepted and not yet confirmed
- **THEN** the action shows as pending and cannot be triggered again for that game

#### Scenario: Confirmed running
- **WHEN** the desktop is observed to be in the launched game
- **THEN** game detail presents the game as running, consistent with how a game started by hand is
  presented

#### Scenario: Could not confirm
- **WHEN** the confirmation window expires with no observation of the game running
- **THEN** the action reports that the start could not be confirmed, rather than that it failed,
  and can be triggered again

#### Scenario: Refused
- **WHEN** the desktop refuses the launch
- **THEN** the action reports the refusal promptly and returns to its offered state

#### Scenario: Reduced motion honored
- **WHEN** the system indicates that animations should be reduced or disabled
- **THEN** the pending state is conveyed without motion and remains legible
