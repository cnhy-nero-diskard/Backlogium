## ADDED Requirements

### Requirement: Only installed Steam applications may be launched
The system SHALL launch an application only where the host's own Steam installation records show
it installed in a configured library. Any other request SHALL be refused without invoking anything
on the host.

#### Scenario: An installed game
- **WHEN** a launch is requested for a game the host reports installed
- **THEN** the host is asked to start it through Steam

#### Scenario: An uninstalled game
- **WHEN** a launch is requested for an owned game that is not installed
- **THEN** the request is refused and nothing is started

#### Scenario: An identifier with no installation record
- **WHEN** a launch is requested for an identifier that has no installation record, including one
  registered on the host as a non-Steam shortcut
- **THEN** the request is refused and no program is started

#### Scenario: Installation records unreadable
- **WHEN** the host's installation records cannot be read or parsed
- **THEN** launches are refused rather than permitted unchecked

#### Scenario: No arguments are accepted
- **WHEN** a launch is requested
- **THEN** the application is started with no caller-supplied arguments

### Requirement: Launch is acknowledged, then separately confirmed
The system SHALL distinguish a launch being accepted by the host from the game being observed to
run. Acceptance SHALL be reported promptly. The app SHALL NOT present a game as running on the
basis of acceptance alone.

#### Scenario: Command accepted
- **WHEN** the agent passes the installation check and invokes Steam
- **THEN** it reports acceptance promptly, and the app shows the launch as pending

#### Scenario: Running state confirmed independently
- **WHEN** the desktop is subsequently observed to be in the launched game
- **THEN** the app presents the game as running

#### Scenario: Acceptance is not a running claim
- **WHEN** a launch has been accepted but the desktop has not been observed in that game
- **THEN** the app presents the launch as pending and does not claim the game is running

#### Scenario: Refusal is immediate
- **WHEN** the agent refuses a launch
- **THEN** the app reports it as refused without waiting for a confirmation window

### Requirement: Unconfirmed launches are reported as uncertain
Where a launch is accepted but never observed to result in the game running, the system SHALL
report that it could not confirm the game started, and SHALL NOT report that the launch failed.

#### Scenario: Confirmation window expires
- **WHEN** the confirmation window passes with no observation of the game running
- **THEN** the app states that it could not confirm the game started, and offers to try again

#### Scenario: Confirmation impossible for want of connectivity
- **WHEN** the app has no path to observe the desktop's presence, such as a local network with no
  internet access
- **THEN** the app states that it cannot confirm because it cannot reach Steam, distinguishing this
  from a launch that produced no observed result

#### Scenario: Late confirmation is still honoured
- **WHEN** the game is observed running after the confirmation window has expired
- **THEN** the app presents the game as running

### Requirement: One launch at a time, and never unrequested
While a launch is pending for a game the system SHALL NOT issue another for it. The system SHALL
NOT retry automatically, and SHALL NOT defer a launch to be delivered when the host later becomes
reachable.

#### Scenario: Repeated request while pending
- **WHEN** a launch is pending for a game
- **THEN** another launch for that game cannot be requested until it resolves

#### Scenario: No automatic retry
- **WHEN** a launch is refused or cannot be confirmed
- **THEN** no further launch is issued without the player asking again

#### Scenario: Unreachable host does not queue
- **WHEN** the host is unreachable
- **THEN** no launch is stored for later delivery, and none occurs when the host next becomes
  reachable

### Requirement: The host records what it was asked to do
The agent SHALL record each launch request it accepts or refuses, with the application requested
and the outcome, on the host.

#### Scenario: An accepted launch is recorded
- **WHEN** a launch is accepted
- **THEN** the host records the application and that it was accepted

#### Scenario: A refused launch is recorded
- **WHEN** a launch is refused
- **THEN** the host records the application and the refusal
