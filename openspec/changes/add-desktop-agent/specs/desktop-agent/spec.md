## ADDED Requirements

### Requirement: The agent never acts on its host
The agent SHALL expose only operations that report state. It SHALL NOT accept any request that
starts, stops, installs, removes, or modifies anything on the host machine.

#### Scenario: No mutating operation is offered
- **WHEN** the agent's exposed operations are enumerated
- **THEN** every one of them is a read of host state

#### Scenario: An unrecognised request is refused
- **WHEN** the agent receives a request naming an operation it does not expose
- **THEN** it refuses the request and changes nothing on the host

### Requirement: The agent is discoverable and reachable without discovery
The agent SHALL advertise itself on the local network for automatic discovery, and the app SHALL
additionally accept a manually entered host address as an equally supported path to the same
agent.

#### Scenario: Discovered automatically
- **WHEN** the app searches for an agent on a network where multicast is permitted
- **THEN** the agent is offered to the player without them typing an address

#### Scenario: Multicast unavailable
- **WHEN** the network blocks multicast, such as under access-point isolation or on a guest network
- **THEN** the player can still reach the agent by entering its address, and the resulting pairing
  is indistinguishable from a discovered one

#### Scenario: Manual entry is not hidden
- **WHEN** the player opens the pairing surface
- **THEN** entering an address is presented as an available choice, not as recovery from a failure

### Requirement: Pairing establishes trust once
Pairing SHALL require a code displayed by the agent and entered on the phone, SHALL result in a
shared secret and a pinned agent identity retained by both sides, and SHALL survive restarts of
both the app and the agent without repeating.

#### Scenario: Successful pairing
- **WHEN** the player enters the code the agent is displaying
- **THEN** the two are paired, and the app retains the secret and the agent's pinned identity

#### Scenario: Pairing survives restarts
- **WHEN** the phone and the host machine have both been restarted since pairing
- **THEN** the app communicates with the agent without pairing again

#### Scenario: The pairing window is bounded
- **WHEN** the code is not used within its window
- **THEN** it expires and no pairing can be completed with it

#### Scenario: Guessing is not practical
- **WHEN** repeated incorrect codes are submitted
- **THEN** further attempts are rate-limited

#### Scenario: Unpairing revokes access
- **WHEN** the player unpairs from the app
- **THEN** the retained secret is discarded and subsequent requests are refused until a new pairing

### Requirement: Communication is confidential and cannot be forged or replayed
All communication between app and agent SHALL be encrypted, SHALL be accepted only from the paired
counterpart, and SHALL be rejected if replayed.

#### Scenario: An unpinned identity is refused
- **WHEN** a different host answers at the paired address and presents a different identity
- **THEN** the app refuses to communicate with it and reports that the agent could not be verified

#### Scenario: An unsigned request is refused
- **WHEN** the agent receives a request not carrying valid proof of the paired secret
- **THEN** the request is refused

#### Scenario: A captured request cannot be reused
- **WHEN** a previously valid request is captured and sent again
- **THEN** the agent refuses it

#### Scenario: A stale request is refused
- **WHEN** a request arrives bearing a timestamp outside the accepted window
- **THEN** the agent refuses it

### Requirement: An unreachable agent degrades the app to its unpaired behaviour
Where no agent is paired, or a paired agent cannot be reached, the app SHALL continue to function
exactly as it does without the feature, and SHALL NOT present the agent's absence as an error
condition of the app itself.

#### Scenario: No agent paired
- **WHEN** the player has never paired an agent
- **THEN** no agent-derived surface appears anywhere in the app

#### Scenario: Host asleep or powered off
- **WHEN** a paired agent cannot be reached
- **THEN** the app reports the desktop as unreachable, retains previously reported state as dated
  information, and remains fully usable

#### Scenario: Phone away from the network
- **WHEN** the phone is not on the same network as the paired agent
- **THEN** the app behaves as it does for an unreachable agent, without repeated failure alerts

#### Scenario: No network at all
- **WHEN** the phone has no network connectivity
- **THEN** every non-agent feature behaves exactly as it does today
