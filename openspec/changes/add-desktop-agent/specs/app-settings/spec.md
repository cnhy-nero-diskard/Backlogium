## ADDED Requirements

### Requirement: Paired desktop section
Settings SHALL provide a section for pairing a desktop agent, showing the current pairing state,
offering both discovery and manual address entry, and allowing the pairing to be removed.

#### Scenario: Nothing paired
- **WHEN** no agent is paired and the player opens the section
- **THEN** it offers to search the local network and to enter an address directly

#### Scenario: Pairing in progress
- **WHEN** the player selects an agent
- **THEN** the section prompts for the code the agent is displaying

#### Scenario: Paired and reachable
- **WHEN** an agent is paired and reachable
- **THEN** the section names the paired machine and shows when its state was last reported

#### Scenario: Paired and unreachable
- **WHEN** a paired agent cannot be reached
- **THEN** the section says so plainly, distinguishing it from not being paired at all

#### Scenario: Unpairing
- **WHEN** the player removes the pairing
- **THEN** the stored secret is discarded, retained installed state is cleared, and every
  agent-derived surface disappears

#### Scenario: Section reflects a failed pairing attempt
- **WHEN** an entered code is rejected
- **THEN** the section reports the failure and allows another attempt without restarting discovery
