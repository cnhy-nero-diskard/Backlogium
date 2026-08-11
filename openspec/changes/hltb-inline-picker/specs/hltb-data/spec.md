## ADDED Requirements

### Requirement: Candidate cover art reference
Retained HowLongToBeat candidates SHALL carry a cover-art reference when the search response supplies
one, so it can be presented without an additional lookup.

#### Scenario: Art reference retained
- **WHEN** a search response supplies a cover-art reference for a candidate
- **THEN** that reference is retained with the candidate as a usable image URL

#### Scenario: Art reference absent
- **WHEN** a search response supplies no cover-art reference for a candidate
- **THEN** the candidate is retained without one and remains fully usable

#### Scenario: Previously cached candidates remain readable
- **WHEN** candidates were cached before cover-art references were retained
- **THEN** they are still readable and selectable, without a cover-art reference and without
  requiring a re-fetch

### Requirement: Candidate lookup without classification
The system SHALL provide a HowLongToBeat candidate lookup that returns scored candidates without
classifying them, without recording a match, and without altering any stored HowLongToBeat data.
This supports correcting a match that was already resolved, where re-running the automatic matcher
would reproduce the same wrong result and discard the candidates needed to fix it.

#### Scenario: Candidates for an already-resolved game
- **WHEN** candidates are looked up for a game whose match is already resolved
- **THEN** the candidates are returned for selection, and the game's stored match, completion
  lengths, match status, and freshness timestamp are all left unchanged

#### Scenario: Candidates returned regardless of confidence
- **WHEN** a lookup returns results that the automatic matcher would resolve confidently
- **THEN** the full scored candidate list is still returned, rather than collapsing to the single
  automatic choice

#### Scenario: A previously corrected match is not overwritten
- **WHEN** the user has manually resolved a game's match and later looks up candidates again
- **THEN** their chosen match remains in effect unless and until they select a different candidate

#### Scenario: Lookup fails
- **WHEN** the candidate lookup fails
- **THEN** no candidates are returned and the game's stored HowLongToBeat data is left intact

#### Scenario: Selecting from a fresh lookup
- **WHEN** the user selects a candidate obtained this way
- **THEN** the match is recorded exactly as it is for a review-flagged game, preserving the existing
  freshness timestamp
