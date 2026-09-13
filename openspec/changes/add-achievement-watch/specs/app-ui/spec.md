## ADDED Requirements

### Requirement: An unlock event is presented in the app when the app is in view
When an achievements-unlocked event is pending and the app is in the foreground, the system SHALL
present it in the app, naming the game and the achievements it carries, and SHALL acknowledge it once
it has been presented. A pending event SHALL survive navigation and process death until presented,
inheriting the progress-event pipeline's delivery guarantee.

#### Scenario: Unlock while the app is open
- **WHEN** the player is in a game with the app open and an unlock event is produced
- **THEN** it is presented in the app, naming the game and the achievements

#### Scenario: Unlock while the app is backgrounded, then reopened
- **WHEN** an unlock event was produced while the app was backgrounded and was not delivered by any
  other surface, and the player opens the app
- **THEN** it is presented then, rather than being lost

#### Scenario: Presented once
- **WHEN** an unlock event has been presented and acknowledged
- **THEN** it is not presented again, including after the app process is killed and relaunched

#### Scenario: Several achievements in one event
- **WHEN** an event carries several achievements
- **THEN** they are presented together as one moment, not as a sequence of separate presentations

#### Scenario: Presentation does not obstruct play
- **WHEN** an unlock event is presented in the app
- **THEN** it is transient and does not require dismissal to continue using the screen beneath it

#### Scenario: An unlock alongside a higher-priority event
- **WHEN** a level-up and an unlock are both pending and the surface presents one
- **THEN** the level-up is presented, and the unlock remains available to be presented after it

#### Scenario: Announced to accessibility services
- **WHEN** an unlock event is presented
- **THEN** the game and the achievements it names are announced

### Requirement: An unlock event is delivered as a notification when the app is not in view
When an achievements-unlocked event is pending and the app is not in the foreground, the system SHALL
post a notification naming the game and the achievements, on a channel distinct from the ongoing
now-playing notification, and SHALL treat that as the event's delivery. Where notification permission
has not been granted, the system SHALL post nothing, surface no error, and leave the event pending for
in-app presentation.

#### Scenario: Notification posted
- **WHEN** an unlock event is produced while the app is not in the foreground and notification
  permission is granted
- **THEN** a notification naming the game and the achievements is posted

#### Scenario: Own channel
- **WHEN** an unlock notification is posted
- **THEN** it is on a channel distinct from the ongoing now-playing notification's, so the two can be
  configured independently

#### Scenario: Ongoing notification unaffected
- **WHEN** an unlock notification is posted while the now-playing notification is showing
- **THEN** the now-playing notification remains in place, unchanged and still silent

#### Scenario: Delivered once across surfaces
- **WHEN** an unlock event has been delivered as a notification
- **THEN** it is not additionally presented in the app when the player next opens it

#### Scenario: No notification permission
- **WHEN** an unlock event is produced while the app is not in the foreground and notification
  permission has not been granted
- **THEN** nothing is posted, no error is surfaced, and the event remains pending for in-app
  presentation

#### Scenario: Opening the app from the notification
- **WHEN** the player taps an unlock notification
- **THEN** the app is opened, and the event is not presented a second time

#### Scenario: Several achievements in one notification
- **WHEN** an event carries several achievements
- **THEN** one notification is posted describing them, not one per achievement
