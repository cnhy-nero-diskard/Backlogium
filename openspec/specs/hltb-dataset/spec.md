# hltb-dataset

## Purpose

Defines the shared HowLongToBeat completion-length dataset that replaces per-device library-wide
scraping: its format and the separation of the Steam-to-HowLongToBeat correspondence from the
completion lengths themselves, how it is discovered and verified from published releases, how it
merges against locally held data without ever displacing a match the user resolved themselves, how
absent coverage is expressed rather than hidden, and the narrow contribution export by which the
dataset grows.

## Requirements

### Requirement: Dataset separates correspondence from completion lengths
The dataset SHALL represent two distinct things: a correspondence from a Steam app id to a
HowLongToBeat entry id, and the four completion lengths belonging to that HowLongToBeat entry. The
two SHALL remain distinguishable in the published format, so that a consumer can adopt the
correspondence without adopting the lengths.

#### Scenario: Correspondence available on its own
- **WHEN** the dataset is read
- **THEN** the Steam-app-id-to-HowLongToBeat-id correspondence is identifiable independently of the
  completion lengths

#### Scenario: Lengths keyed by HowLongToBeat entry
- **WHEN** completion lengths are read from the dataset
- **THEN** they are keyed by the HowLongToBeat entry id rather than by the Steam app id, so two
  Steam entries corresponding to the same HowLongToBeat entry carry one set of lengths

#### Scenario: A correspondence with no lengths
- **WHEN** the dataset carries a correspondence for a game but no completion lengths for its
  HowLongToBeat entry
- **THEN** the correspondence is still usable, and the game is treated as matched with unknown
  lengths rather than as unmatched

### Requirement: Dataset carries no personal data
The published dataset SHALL contain only Steam app ids, HowLongToBeat entry ids, completion
lengths, and the dataset's own version and publication time. It SHALL NOT contain playtime,
sessions, achievements, streaks, account identifiers, or any other per-user value.

#### Scenario: Dataset contents are exhaustively bounded
- **WHEN** the dataset is inspected
- **THEN** every value in it is a Steam app id, a HowLongToBeat entry id, a completion length, or
  dataset-level version metadata

#### Scenario: A contribution carrying more is not published
- **WHEN** a contribution contains a field outside that set
- **THEN** it is rejected rather than published

### Requirement: Dataset discovery and verification
The system SHALL discover the dataset from the project's published releases, SHALL verify what it
downloaded before applying any of it, and SHALL leave locally held data untouched when
verification fails.

#### Scenario: A newer dataset is published
- **WHEN** a check finds a published dataset newer than the one already applied
- **THEN** it is offered for download

#### Scenario: Verification fails
- **WHEN** a downloaded dataset fails verification
- **THEN** none of it is applied, locally held HowLongToBeat data is unchanged, and the failure is
  reported

#### Scenario: Download fails
- **WHEN** a dataset download cannot complete
- **THEN** the previously applied dataset remains in effect and the app remains fully usable

#### Scenario: Dataset check is not part of app startup
- **WHEN** the app starts
- **THEN** no dataset check is issued as a consequence of starting

#### Scenario: Already current
- **WHEN** a check finds no dataset newer than the one already applied
- **THEN** nothing is downloaded and the outcome is reported as up to date

### Requirement: Dataset application is all-or-nothing
Applying a dataset SHALL either complete in full or leave locally held HowLongToBeat data exactly
as it was. A partially applied dataset SHALL NOT be observable.

#### Scenario: Interrupted application
- **WHEN** dataset application is interrupted before it completes
- **THEN** locally held HowLongToBeat data reflects either the previous state or the fully applied
  new state, never a mixture

### Requirement: A user's own resolved match is never displaced
Applying a dataset SHALL NOT change a game whose HowLongToBeat match the user resolved manually,
even when the dataset carries a different correspondence for that game. The dataset SHALL be able
to update the completion lengths of the entry the user chose.

#### Scenario: Dataset disagrees with a manual resolution
- **WHEN** the dataset carries a different HowLongToBeat entry for a game the user resolved
  manually
- **THEN** the user's chosen entry remains in effect and the dataset's correspondence is not applied
  to that game

#### Scenario: Lengths still refresh for a manually chosen entry
- **WHEN** the dataset carries newer completion lengths for the HowLongToBeat entry the user chose
- **THEN** those lengths are applied, because the user resolved which entry is correct, not what its
  lengths are

#### Scenario: Dataset agrees with a manual resolution
- **WHEN** the dataset carries the same HowLongToBeat entry the user resolved manually
- **THEN** the game is unchanged apart from its lengths, and it is not returned to review

### Requirement: Dataset supersedes an automatic match, not a manual one
Applying a dataset SHALL replace a match that was resolved automatically or left flagged for
review, because the dataset represents a reviewed answer and the automatic match represents a
guess.

#### Scenario: Dataset covers an automatically matched game
- **WHEN** the dataset carries a correspondence for a game matched automatically
- **THEN** the dataset's correspondence and lengths take effect

#### Scenario: Dataset covers a game flagged for review
- **WHEN** the dataset carries a correspondence for a game flagged as needing review
- **THEN** the game resolves to the dataset's entry and is removed from the review queue

#### Scenario: Dataset covers a game recorded as unmatched
- **WHEN** the dataset carries a correspondence for a game previously recorded as having no match
- **THEN** the game resolves to the dataset's entry

### Requirement: Rows record where they came from
Every stored HowLongToBeat row SHALL record whether its values came from the dataset or from a
lookup performed on the device, so precedence and age can be reasoned about without inferring them.

#### Scenario: Provenance is recorded on application
- **WHEN** a row is written from the dataset
- **THEN** it records that the dataset is its origin

#### Scenario: Provenance is recorded on lookup
- **WHEN** a row is written from a lookup performed on the device
- **THEN** it records that a device lookup is its origin

#### Scenario: A device lookup replaces a dataset row
- **WHEN** the user looks a game up on the device and the result is stored
- **THEN** the row's origin becomes the device lookup, and a later dataset application does not
  revert it unless the user's row was an automatic match rather than a manual resolution

### Requirement: A dataset row carries the dataset's age, not the import time
A row applied from the dataset SHALL carry the time the dataset's values were gathered, not the
time the dataset was applied locally, so that an imported row is not treated as freshly obtained.

#### Scenario: Age reflects the dataset
- **WHEN** a row is applied from a dataset published some time ago
- **THEN** the row's recorded age is the dataset's, not the moment of application

#### Scenario: Re-applying the same dataset does not rejuvenate rows
- **WHEN** the same dataset is applied again
- **THEN** the ages of its rows are unchanged

### Requirement: Absent coverage is expressed, not disguised
A game the dataset does not cover SHALL be distinguishable from a game for which a lookup
established that no HowLongToBeat entry exists. Absence of coverage SHALL NOT be presented as
absence of a match.

#### Scenario: Game absent from the dataset
- **WHEN** a game is not present in the applied dataset and has never been looked up on the device
- **THEN** it is recorded and presented as not covered by the dataset, distinct from having no
  match

#### Scenario: Not-covered invites a deliberate lookup
- **WHEN** a game is not covered by the dataset
- **THEN** looking that game up on the device is offered as an explicit action

#### Scenario: A lookup that finds nothing
- **WHEN** a not-covered game is looked up on the device and the lookup establishes that no
  HowLongToBeat entry exists
- **THEN** the game is recorded as having no match, no longer as not covered

### Requirement: The app is fully usable with no dataset
The system SHALL function with no dataset ever applied, with no network, and with no access to the
release service. The dataset SHALL be additive.

#### Scenario: Dataset never applied
- **WHEN** no dataset has ever been applied
- **THEN** every HowLongToBeat surface remains usable and games are presented as not covered

#### Scenario: No network
- **WHEN** the device has no network
- **THEN** the applied dataset continues to serve completion lengths and no dataset check is
  attempted

### Requirement: Contribution export is narrow by construction
The system SHALL provide an export producing a contribution file in the dataset's format. The
export SHALL include only games whose HowLongToBeat match is resolved, SHALL include only the app
id, HowLongToBeat entry id, and completion lengths for each, and SHALL exclude games flagged for
review and games recorded as having no match.

#### Scenario: Only resolved games are exported
- **WHEN** the contribution export runs
- **THEN** it contains a row for each resolved game and no row for a game flagged for review or
  recorded as having no match

#### Scenario: No personal values are exported
- **WHEN** a contribution file is inspected
- **THEN** it contains no playtime, session, achievement, streak, or account value

#### Scenario: Nothing to contribute
- **WHEN** no game has a resolved match
- **THEN** the export reports that there is nothing to contribute rather than producing an empty
  file presented as a contribution

### Requirement: Export discloses what a contribution reveals
Before producing a contribution file, the system SHALL state that the file identifies which Steam
app ids the user owns and that contributing publishes that list.

#### Scenario: Disclosure precedes export
- **WHEN** the user asks to produce a contribution file
- **THEN** the disclosure is presented before the file is written

#### Scenario: Declining after disclosure
- **WHEN** the user declines after reading the disclosure
- **THEN** no file is written

### Requirement: Contributions are validated before publication
A contribution SHALL be validated before it can enter the published dataset. Validation SHALL
reject a file whose identifiers are not positive integers, whose completion lengths are negative or
implausibly large, or which contains more than one row for the same Steam app id.

#### Scenario: Malformed identifier
- **WHEN** a contribution contains a non-positive or non-integer Steam app id or HowLongToBeat entry id
- **THEN** the contribution is rejected and the offending row is identified

#### Scenario: Implausible length
- **WHEN** a contribution contains a negative completion length, or one beyond a documented upper
  bound
- **THEN** the contribution is rejected and the offending row is identified

#### Scenario: Duplicate rows within one contribution
- **WHEN** a contribution contains two rows for the same Steam app id
- **THEN** the contribution is rejected

### Requirement: Merge conflicts on correspondence are not resolved automatically
Merging a contribution SHALL block when it maps a Steam app id to a different HowLongToBeat entry
than the published dataset already does, because one of the two is wrong and only a person can say
which. Differing completion lengths for the same HowLongToBeat entry SHALL NOT block; the more
recently gathered values SHALL win.

#### Scenario: Correspondence disagreement
- **WHEN** a contribution maps a Steam app id to a HowLongToBeat entry different from the published
  one
- **THEN** the merge does not complete, and the conflict is reported with both correspondences

#### Scenario: Lengths differ for the same entry
- **WHEN** a contribution carries different completion lengths for a HowLongToBeat entry already in
  the dataset
- **THEN** the merge completes using the more recently gathered lengths

#### Scenario: Contribution adds a new game
- **WHEN** a contribution maps a Steam app id the dataset does not yet cover
- **THEN** the row is added

#### Scenario: Contribution is entirely redundant
- **WHEN** every row in a contribution already matches the published dataset
- **THEN** the merge completes and the dataset is unchanged

### Requirement: Merge output is deterministic
Merging SHALL produce byte-identical output for the same inputs, in a stable order independent of
contribution order, so that a review of a proposed change shows exactly the rows it adds or alters
and nothing else.

#### Scenario: Same inputs, same output
- **WHEN** the same contributions are merged into the same dataset twice
- **THEN** the two outputs are byte-identical

#### Scenario: Contribution order does not matter
- **WHEN** the same set of contributions is merged in a different order, with no correspondence
  conflicts among them
- **THEN** the output is byte-identical

#### Scenario: A merge that changes nothing produces no difference
- **WHEN** a redundant contribution is merged
- **THEN** the output is byte-identical to the input dataset

### Requirement: Dataset publication is independent of application releases
The dataset SHALL be published under a version series distinct from the application's, so that
completion data can be published without releasing a new application version, and so that
application update discovery does not consider a dataset publication an application update.

#### Scenario: Dataset published alone
- **WHEN** a dataset is published and no application release accompanies it
- **THEN** devices can obtain the new dataset and no application update is offered on its account

#### Scenario: Application update discovery ignores dataset publications
- **WHEN** application update discovery runs and the most recent publication is a dataset
- **THEN** it is not considered, and no application update is offered on its account
