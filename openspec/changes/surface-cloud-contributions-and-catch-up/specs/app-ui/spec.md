## ADDED Requirements

### Requirement: History identifies proven cloud contributions at session level

History SHALL display a restrained cloud mark only beside a session for which a cloud contribution is recorded and the reader is configured. The mark SHALL identify recovered presence-derived play and cloud-informed timing of Steam-reported minutes as independent facts, each with its own full/partial qualification; both facts MAY be shown for one session. It SHALL NOT mark a game, entire day, achievement, or progress total merely because the reader is connected, and SHALL NOT present an `UNKNOWN` fact as a contribution. The meaning SHALL be available as text and to assistive technology, not through color or icon alone.

#### Scenario: Shared-game play recovered
- **WHEN** a visible session has recorded recovered-play contribution
- **THEN** History shows a cloud mark at the session and explains that cloud observations helped recover the play, qualifying partial contributions

#### Scenario: Owned-game minutes placed
- **WHEN** a visible session has recorded timing contribution
- **THEN** History explains that cloud observations informed its timing and Steam supplied the minutes

#### Scenario: Both contribution facts are recorded
- **WHEN** one visible session has both recovered-play and timing-informed facts
- **THEN** History exposes both explanations and qualifies the partial state of each independently

#### Scenario: One fact is known and the other is unknown
- **WHEN** a session has a recorded contribution for one fact and `UNKNOWN` for the other
- **THEN** History presents only the known contribution and does not infer the unknown fact

#### Scenario: Ordinary or legacy session
- **WHEN** a session has no recorded cloud contribution, including an older session of unknown provenance
- **THEN** History shows no cloud mark for it

#### Scenario: Cloud reader removed
- **WHEN** the reader is no longer configured
- **THEN** History shows no cloud-derived mark or Cloud activity entry, while recorded play remains in History

#### Scenario: Accessibility and font scaling
- **WHEN** the player uses assistive technology or larger system text
- **THEN** the contribution explanation remains available, legible, and separate from the existing day/game expand and collapse actions

### Requirement: History offers a focused Cloud activity explanation when relevant

When the visible History period includes a recorded cloud contribution, History SHALL offer a compact Cloud activity entry leading to a detail view. That detail SHALL lead with the sessions actually recovered or timed using cloud evidence in the same period, link back to the affected play, and then explain reader success, observation freshness where known, and any partial or unknown coverage. A session with both facts MAY appear in both groups; group counts SHALL describe contribution facts rather than imply that the groups are disjoint session sets. It SHALL use local stored contribution evidence to render offline, avoid presenting missing observations as proof of no play, and leave raw per-interval comparison in Diagnostics. Hidden games SHALL be excluded from its counts, names, and links.

#### Scenario: Relevant history has contributions
- **WHEN** the visible History period contains one or more visible sessions with proven cloud contributions
- **THEN** a quiet Cloud activity entry and a detail explaining those contributions are available

#### Scenario: No contribution in the visible period
- **WHEN** the visible History period has no eligible session with a recorded cloud contribution
- **THEN** no Cloud activity entry occupies History

#### Scenario: Detail has no network
- **WHEN** the player opens Cloud activity offline
- **THEN** the affected sessions and stored read status remain readable without waiting for a network request

#### Scenario: Partial evidence
- **WHEN** the available observation window is incomplete or its coverage is unknown
- **THEN** the detail describes that limit rather than claiming continuous coverage or zero play

#### Scenario: Hidden game contributes
- **WHEN** a hidden game's session has cloud contribution provenance
- **THEN** the game's identity and contribution are omitted from Cloud activity and its visible counts

#### Scenario: Reader has failed since its last success
- **WHEN** a recent reader attempt failed after a prior success
- **THEN** the detail preserves the last known contributions, distinguishes the failed attempt from the prior success, and does not present either as a live poller-health guarantee
