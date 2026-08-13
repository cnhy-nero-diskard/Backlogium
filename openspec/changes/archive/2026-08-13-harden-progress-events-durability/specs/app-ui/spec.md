## MODIFIED Requirements

### Requirement: Celebratory inline animations
The system SHALL play an inline animation within the Home screen's Level card when the
player's level increments. The system SHALL play an inline animation within the Home screen's
Streak card when a durable streak-milestone progress event is pending, and SHALL acknowledge that
event only once the animation has actually been presented to completion, so the animation is
driven by the durable event rather than by the streak's current position. A pending
streak-milestone event SHALL remain deliverable across navigation away from and back to Home, and
across an app process death, until it has been acknowledged this way.

#### Scenario: Level increments
- **WHEN** the player's level increases from its previous value
- **THEN** an inline animation plays within the Level card

#### Scenario: Streak reaches a weekly milestone
- **WHEN** a streak-milestone progress event carrying a milestone not previously delivered is
  pending
- **THEN** an inline animation plays within the Streak card, and the event is acknowledged once
  the animation completes

#### Scenario: Streak not at a milestone
- **WHEN** no streak-milestone progress event is pending
- **THEN** no milestone animation plays within the Streak card

#### Scenario: Milestone earned while Home is not shown
- **WHEN** a background sync earns a streak-milestone event while Home is not composed
- **THEN** the milestone animation plays within the Streak card the next time Home is shown,
  rather than being missed

#### Scenario: Acknowledged milestone does not replay
- **WHEN** a streak-milestone event has been presented and acknowledged, and Home is recomposed or
  the app process is killed and relaunched
- **THEN** the milestone animation does not play again for that milestone
