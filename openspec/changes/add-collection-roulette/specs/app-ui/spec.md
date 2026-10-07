## ADDED Requirements

### Requirement: Custom collection overview offers optional roulette
An operable custom collection overview SHALL offer roulette when at least two members satisfy persistent eligibility. Home teasers, collection management forms, derived collections, and archived/inoperable collections SHALL not offer it. Existing overview information/actions SHALL remain usable.

#### Scenario: Operable shortlist
- **WHEN** a custom overview has at least two eligible visible games
- **THEN** a labelled random-choice action is available

#### Scenario: Derived or inoperable collection
- **WHEN** the collection is derived, archived, or otherwise inoperable
- **THEN** roulette is absent

#### Scenario: Overview remains functional
- **WHEN** roulette is dismissed
- **THEN** the normal member/detail/management flow remains usable

### Requirement: Roulette result communicates the actual decision and next step
The result SHALL show the selected game's identity, actual sampled count, relevant exclusion explanation, accessible detail/re-spin/dismiss actions, and an explicit queue action only where applicable. It SHALL use collection-consistent styling without milestone/recommendation claims, and SHALL explain membership invalidation.

#### Scenario: Sample disclosure
- **WHEN** a re-spin excludes the previous result
- **THEN** the displayed count and explanation describe the actual pool rather than all persistent members

#### Scenario: Open detail
- **WHEN** a valid selected game is opened
- **THEN** normal game detail navigation occurs without changing goals or queue order

#### Scenario: Queue write fails
- **WHEN** the explicit Move next mutation fails
- **THEN** failure is shown and no committed-success haptic is emitted
