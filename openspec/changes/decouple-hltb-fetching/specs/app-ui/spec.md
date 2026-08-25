## ADDED Requirements

### Requirement: Completion-times dataset presentation
The system SHALL present the state of the applied completion-times dataset: when its data was
gathered, how many of the user's games it covers, and a control that checks for a newer one. While
a dataset is downloading or being applied, the system SHALL reflect that, and SHALL report the
outcome when it finishes.

#### Scenario: Dataset state is visible
- **WHEN** the completion-times surface is shown and a dataset has been applied
- **THEN** it presents when the dataset's data was gathered and how many of the user's games it
  covers

#### Scenario: No dataset applied
- **WHEN** no dataset has ever been applied
- **THEN** the surface says so and offers to obtain one, rather than presenting an error or an
  empty value

#### Scenario: Download and application in progress
- **WHEN** a dataset is downloading or being applied
- **THEN** the surface reflects that and the check control is unavailable until it finishes

#### Scenario: Newer dataset applied
- **WHEN** a newer dataset finishes being applied
- **THEN** the surface reports how many games gained completion lengths, and the Library reflects
  them without the user reopening it

#### Scenario: Already current
- **WHEN** a check finds no newer dataset
- **THEN** the surface reports that the dataset is up to date

#### Scenario: Check or download fails
- **WHEN** a check or download cannot complete
- **THEN** the surface reports that it did not complete, remains fully usable, and the previously
  applied dataset is still presented as in effect

### Requirement: Games not covered by the dataset are identifiable
The Library SHALL distinguish a game that the applied dataset does not cover and that has never
been looked up on the device from a game for which a lookup established that no HowLongToBeat entry
exists, and SHALL let the user filter to the uncovered games so a selection can be made from them.

#### Scenario: Not-covered state shown
- **WHEN** the Library shows a game absent from the applied dataset that has never been looked up
- **THEN** the game is presented as not covered, distinctly from a game presented as having no match

#### Scenario: Filtering to uncovered games
- **WHEN** the user filters the Library to games not covered by the dataset
- **THEN** only those games are listed, and a selection can be made from them for lookup

#### Scenario: Lookup clears the not-covered state
- **WHEN** a not-covered game is looked up and the lookup completes
- **THEN** the game is presented by its lookup outcome — matched, needing review, or no match — and
  no longer as not covered

### Requirement: Contribution export surface
The system SHALL provide a control that produces a completion-times contribution file, SHALL state
before producing it that the file identifies which games the user owns and that contributing
publishes that list, and SHALL let the user choose where the file is written.

#### Scenario: Disclosure before writing
- **WHEN** the user activates the contribution export control
- **THEN** what the file reveals is stated before any file is written

#### Scenario: Declining after disclosure
- **WHEN** the user declines after reading the disclosure
- **THEN** no file is written and the surface returns to its previous state

#### Scenario: Choosing a destination
- **WHEN** the user proceeds past the disclosure
- **THEN** they choose where the file is written, and the outcome is reported

#### Scenario: Nothing to contribute
- **WHEN** no game has a resolved HowLongToBeat match
- **THEN** the control reports that there is nothing to contribute and writes no file

## MODIFIED Requirements

### Requirement: Targeted HowLongToBeat refresh
The system SHALL let the user select multiple games in the Library and run a HowLongToBeat lookup
over only that selection. This is the only way to look up more than one game; no control looks up
the library as a whole. The system SHALL show the progress of a running selection lookup, including
how many games have been processed out of the total and a log of each processed game with its
outcome, and SHALL let the user stop it, keeping the data already obtained.

#### Scenario: Entering selection mode
- **WHEN** the user long-presses a game row
- **THEN** selection mode is entered with that game selected, and the number of selected games is
  shown

#### Scenario: Refreshing the selection
- **WHEN** the user runs the HowLongToBeat lookup on a selection
- **THEN** only the selected games are looked up

#### Scenario: Selection preserved while filtering
- **WHEN** a search filter hides a selected game
- **THEN** it remains part of the selection, and the visible selected count continues to include it

#### Scenario: Leaving selection mode
- **WHEN** the user clears the selection or navigates away
- **THEN** selection mode is exited and no selection is retained

#### Scenario: Tap behavior unchanged
- **WHEN** the user taps a game row while not in selection mode
- **THEN** the game's detail screen opens as it does today

#### Scenario: Progress while looking up a selection
- **WHEN** a selection lookup is running
- **THEN** the Library shows the number of games processed out of the total as a progress indicator

#### Scenario: Per-game log
- **WHEN** each game in the selection is processed
- **THEN** a log entry names the game and its outcome: matched, needs review, no match, or lookup
  failed

#### Scenario: Stopping a running lookup
- **WHEN** the user stops a running selection lookup
- **THEN** the lookup ends, the controls become available again, and every game already processed
  keeps the data it received

#### Scenario: Lookup completes
- **WHEN** a selection lookup finishes
- **THEN** the progress indicator resolves, the controls become available again, and any games
  needing review become available in the review surface

#### Scenario: No whole-library control
- **WHEN** the Library's HowLongToBeat controls are presented
- **THEN** none of them starts a lookup over the library as a whole

### Requirement: Per-game HowLongToBeat status and refresh
The system SHALL show each game's HowLongToBeat state in the Library — a lookup in progress, a
failed lookup, a stored match result, or not covered by the dataset — and SHALL let the user trigger
a fresh single-game lookup.

#### Scenario: Per-game status is visible
- **WHEN** the Library shows a game that has stored HowLongToBeat data (matched, needing review, or no match) or an in-progress lookup
- **THEN** the game displays its current HowLongToBeat state

#### Scenario: Uncovered game status
- **WHEN** the Library shows a game with no stored HowLongToBeat data that the applied dataset does
  not cover
- **THEN** the game displays that it is not covered

#### Scenario: Refreshing a single game
- **WHEN** the user triggers a HowLongToBeat refresh for a single game
- **THEN** the system performs a fresh lookup for that game regardless of cached or dataset-supplied
  data and reflects the in-progress state while it runs

#### Scenario: Single-game lookup fails
- **WHEN** a single-game HowLongToBeat lookup fails
- **THEN** the failure is surfaced for that game and its cached HowLongToBeat data is not overwritten or cleared

### Requirement: HLTB match review
The system SHALL provide a surface listing games flagged as needing an HLTB match, and SHALL let the
user open a flagged game and select the correct HowLongToBeat entry from its candidates. This
surface serves the games the dataset does not resolve; the entry point to it SHALL be presented only
when at least one game is flagged as needing review.

#### Scenario: Reviewing flagged games
- **WHEN** the user opens the match-review surface and games are flagged as needing review
- **THEN** each flagged game is listed with its candidate HowLongToBeat entries available for selection

#### Scenario: Confirming a match
- **WHEN** the user selects the correct candidate for a flagged game
- **THEN** the game is marked resolved, its completion length becomes available to the goal and gamification features, and it is removed from the review list

#### Scenario: No games need review
- **WHEN** the user opens the match-review surface and no games are flagged
- **THEN** the surface indicates there is nothing to review

#### Scenario: Entry point hidden when nothing is flagged
- **WHEN** no games are flagged as needing review
- **THEN** no entry point to the match-review surface is presented

#### Scenario: Entry point shown with a count
- **WHEN** one or more games are flagged as needing review
- **THEN** the entry point is presented and indicates how many games are awaiting review

#### Scenario: A dataset resolves a flagged game
- **WHEN** an applied dataset resolves a game that was flagged for review
- **THEN** the game leaves the review list and the entry point's count reflects its departure

## REMOVED Requirements

### Requirement: Batch HowLongToBeat refresh progress
**Reason**: This requirement describes the progress presentation of a library-wide HowLongToBeat
sweep, which this change removes. Its substance — a processed-of-total indicator and a per-game
outcome log — is not lost: it moves onto the explicit multi-selection lookup, which is now the only
multi-game path, under the modified "Targeted HowLongToBeat refresh" above.

**Migration**: The progress indicator and per-game log are specified by "Targeted HowLongToBeat
refresh". The scenario covering progress surviving a screen change has no successor, because a
selection lookup runs while the user is watching it rather than as detached background work.

### Requirement: Stopping a batch HowLongToBeat refresh
**Reason**: The batch refresh this governs no longer exists. Stopping and keeping already-fetched
data carries over to the selection lookup; the resume-without-re-fetching and forced-restart
scenarios do not, because both are defined in terms of the freshness gate that this change removes.

**Migration**: Stopping a selection lookup, and keeping what it already obtained, are specified by
"Targeted HowLongToBeat refresh". Continuing after a stop is now done by selecting the remaining
games — an explicit selection always looks its games up regardless of how recently their data was
gathered, so there is nothing left for a force option to override.

### Requirement: Refresh HowLongToBeat library trigger
**Reason**: This is the control that starts a library-wide sweep. Removing the sweep removes its
trigger; leaving a one-tap whole-library control in place would preserve the request volume the
change exists to eliminate.

**Migration**: Library-wide completion lengths now arrive by applying the shared dataset, presented
by "Completion-times dataset presentation" above. Games the dataset does not cover are found with
the not-covered filter in "Games not covered by the dataset are identifiable" and looked up as an
explicit selection.
