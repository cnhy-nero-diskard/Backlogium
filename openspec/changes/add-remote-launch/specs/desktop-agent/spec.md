## MODIFIED Requirements

### Requirement: The agent never acts on its host
The agent SHALL expose exactly one operation that acts on the host: starting an installed Steam
application through Steam itself. Every other operation it exposes SHALL be a read of host state.
The agent SHALL NOT accept any request that installs, removes, updates, or stops anything, that
changes host settings, or that starts anything other than an installed Steam application.

#### Scenario: Only one operation acts
- **WHEN** the agent's exposed operations are enumerated
- **THEN** exactly one changes anything on the host, and it starts an installed Steam application

#### Scenario: The acting operation goes through Steam
- **WHEN** the agent starts an application
- **THEN** it does so by invoking Steam, never by executing a program directly

#### Scenario: An unrecognised request is refused
- **WHEN** the agent receives a request naming an operation it does not expose
- **THEN** it refuses the request and changes nothing on the host

#### Scenario: No operation stops or removes anything
- **WHEN** a request is made to stop a running game, uninstall an application, or alter host
  settings
- **THEN** no such operation exists to accept it

#### Scenario: Reporting operations remain read-only
- **WHEN** the agent serves its identity or its installed-application report
- **THEN** nothing on the host changes
