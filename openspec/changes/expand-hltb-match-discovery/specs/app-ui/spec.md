## ADDED Requirements

### Requirement: Visually distinct HLTB candidate cards
The HLTB match center SHALL present each candidate as a distinct adaptive-grid card containing its
cover art, HLTB name, every available completion-length metric, compact match guidance, a dedicated
external HLTB link, and an explicit match-selection action.

#### Scenario: Candidate card has complete available context
- **WHEN** an HLTB candidate is shown in the match center
- **THEN** its card shows its cover and name
- **AND** shows each available Main Story, Main + Extras, Completionist, and All Styles length without rendering absent metrics as zero

#### Scenario: Candidate cover is unavailable
- **WHEN** a candidate has no cover reference or its cover fails to load
- **THEN** the card shows a themed placeholder with unchanged geometry and remains selectable

#### Scenario: Narrow review viewport
- **WHEN** available width cannot support multiple readable candidate cards
- **THEN** the adaptive grid uses one column and keeps every candidate reachable by scrolling

#### Scenario: Wider review viewport
- **WHEN** available width supports multiple minimum-width cards
- **THEN** the grid uses additional columns without truncating required candidate context

#### Scenario: Candidate external link is opened
- **WHEN** the user activates a candidate's HLTB link
- **THEN** the canonical HLTB game page opens outside Backlogium
- **AND** the candidate is not selected or persisted

#### Scenario: Candidate match action is used
- **WHEN** the user activates `Use match` on a candidate card
- **THEN** that candidate resolves the current Steam game's HLTB match through the existing manual-resolution behavior

### Requirement: Steam identity remains separate from HLTB candidates
The match center SHALL present the current Steam game's artwork or icon, Steam title, current HLTB
state, and one external Steam Store link in a header separate from the candidate grid.

#### Scenario: Reviewing one Steam game
- **WHEN** a game is selected in the match center
- **THEN** its Steam identity and match state are visible above its HLTB candidates

#### Scenario: Steam Store link is opened
- **WHEN** the user activates the Steam link in the review header
- **THEN** the app opens the Store page derived from that game's Steam app id outside Backlogium
- **AND** no HLTB candidate is selected

#### Scenario: Multiple games require attention
- **WHEN** more than one game is reviewable or unmatched
- **THEN** the user can move between games with a visible current-versus-total position
- **AND** candidates remain clearly associated with the selected Steam game

### Requirement: Broader-search rescue presentation
For an unmatched game, the match center and the game's HLTB management flow SHALL present an
explicit `Try broader search` action with distinct searching, failed, exhausted, and candidates-found
states. The action SHALL not appear as a normal primary-search replacement.

#### Scenario: Unmatched game is opened
- **WHEN** a game's completed HLTB state is unmatched
- **THEN** `Try broader search` is offered with text explaining that relaxed title variants will be searched

#### Scenario: Broader search is running
- **WHEN** the user triggers broader search
- **THEN** the rescue surface shows an in-progress state and prevents a duplicate trigger for that game

#### Scenario: Broader search finds candidates
- **WHEN** broader search returns one or more candidates
- **THEN** the game moves to needs-review state
- **AND** the candidates are shown for manual selection with broader-result guidance

#### Scenario: Broader search is exhausted
- **WHEN** all broader queries succeed without candidates
- **THEN** the surface states that no broader matches were found
- **AND** keeps the manual HLTB link action available

#### Scenario: Broader search fails
- **WHEN** broader search fails for transport or parsing reasons
- **THEN** a retryable failure is shown distinctly from no results
- **AND** the game remains unmatched

### Requirement: Manual HLTB game-link entry and preview
The match center SHALL provide a last-resort HLTB game-link action for unmatched and needs-review
games, and the inline change-match picker SHALL expose the same action. The flow SHALL validate the
input, report lookup state, show the linked candidate preview, and require confirmation before
changing the match.

#### Scenario: User opens manual link entry
- **WHEN** the user activates the manual HLTB link action
- **THEN** an input is shown identifying the accepted HLTB game-page format

#### Scenario: Link is invalid
- **WHEN** the user submits a link that fails HLTB game-link validation
- **THEN** a field-level validation message is shown
- **AND** no lookup or stored match change occurs

#### Scenario: Linked game is loading
- **WHEN** a valid link has been submitted and direct lookup is in progress
- **THEN** the flow shows progress and prevents duplicate submission

#### Scenario: Linked candidate is ready
- **WHEN** direct lookup returns the linked game
- **THEN** a preview shows the original Steam title beside the HLTB cover, title, and all available lengths
- **AND** presents separate `Confirm match` and dismiss actions

#### Scenario: User confirms linked candidate
- **WHEN** the user activates `Confirm match`
- **THEN** the linked entry becomes the resolved HLTB match and the updated status is reflected immediately

#### Scenario: User dismisses manual link flow
- **WHEN** the user dismisses link entry or candidate preview
- **THEN** the prior match state and retained candidates remain unchanged

#### Scenario: Direct lookup fails or is not found
- **WHEN** a valid link cannot be loaded, parsed, or found
- **THEN** the flow reports the specific available failure category and permits correction or retry
- **AND** preserves the existing match state

## MODIFIED Requirements

### Requirement: HLTB match review
The system SHALL provide an always-accessible HLTB match center covering games flagged as needing a
match and games whose completed primary search found no match. It SHALL let the user review one game
at a time and select the correct HowLongToBeat entry from its candidates. The entry point's attention
badge SHALL count only games that already have ambiguous candidates awaiting review, while unmatched
games remain discoverable in the destination without inflating that badge.

#### Scenario: Reviewing flagged games
- **WHEN** the user opens the match center and games are flagged as needing review
- **THEN** each flagged game is reachable with its candidate HowLongToBeat entries available for selection

#### Scenario: Confirming a match
- **WHEN** the user selects the correct candidate for a flagged game
- **THEN** the game is marked resolved, its completion length becomes available to the goal and gamification features, and it is removed from the reviewable set

#### Scenario: Only unmatched games exist
- **WHEN** no game has ambiguous retained candidates but at least one game is unmatched
- **THEN** the match-center entry remains available
- **AND** the attention badge is absent or zero while unmatched games remain reachable for rescue

#### Scenario: No games need review or rescue
- **WHEN** the user opens the match center and no game is flagged or unmatched
- **THEN** the surface indicates there is nothing to review or rescue

#### Scenario: Match center entry has no attention items
- **WHEN** no ambiguous candidate set awaits a decision
- **THEN** the HLTB match-center menu item remains available without an attention badge
