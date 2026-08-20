## ADDED Requirements

### Requirement: Releases derive audience-specific notes from one model
The release workflow SHALL deterministically compose one release-note model from the current tag, the previous published valid semver release in the same history, and the pull requests in that comparison. It SHALL derive both the GitHub release body and a versioned structured-notes asset from that same model.

#### Scenario: User-visible and repository outputs are generated
- **WHEN** a valid release tag passes the existing release gates
- **THEN** the workflow generates a GitHub Markdown body and a structured-notes document that describe the same release tag and changes

#### Scenario: Pull request supplies user-facing notes
- **WHEN** a merged pull request in the release range contains one or more user-facing release-note entries
- **THEN** those entries appear in the appropriate user-facing section and the pull request remains linked from the technical details

#### Scenario: Pull request explicitly has no user-visible change
- **WHEN** a merged pull request marks its release note as `None`
- **THEN** it is omitted from user-facing sections and remains available in the technical details

#### Scenario: Legacy pull request lacks release-note metadata
- **WHEN** a normally user-visible merged pull request has no usable release-note entry
- **THEN** the workflow emits a warning and uses its cleaned conventional title as a non-blocking fallback

### Requirement: Release notes separate product changes from technical details
Published release notes SHALL present concise user-facing features, fixes, performance changes, or maintenance status before developer-oriented pull-request details. Technical entries SHALL remain linked on the GitHub release without exposing their handles, raw URLs, or conventional prefixes as the primary product summary.

#### Scenario: Release contains user-visible changes
- **WHEN** one or more user-facing release-note entries are composed
- **THEN** the GitHub release shows categorized plain-language bullets before a collapsible technical section

#### Scenario: Release contains only internal changes
- **WHEN** every pull request in the comparison is marked or classified as internal
- **THEN** the release states that it is a maintenance release with no user-visible feature changes and lists the changes in technical details

#### Scenario: Consecutive releases identify the same commit
- **WHEN** the current and previous published release tags resolve to the same commit
- **THEN** the current release states that it contains no application changes since the previous release and does not repeat an older comparison's notes

#### Scenario: Full comparison is available
- **WHEN** a previous release tag exists
- **THEN** the GitHub release provides a named full-changelog link for the explicit previous-to-current tag comparison

### Requirement: Release surfaces use recognizable product and version names
The release workflow SHALL identify its Actions run, jobs, GitHub release, installable artifact, digest, and structured notes with readable product and version context while preserving the existing `vX.Y.Z` tag.

#### Scenario: Release run is listed in Actions
- **WHEN** a release workflow is triggered by a tag
- **THEN** its run name identifies that tag as a Backlogium release and its jobs describe validation and publication work

#### Scenario: Release is published
- **WHEN** a release is published for `v1.8.0`
- **THEN** its title identifies `Backlogium 1.8.0` and its assets use versioned Backlogium filenames for the APK, matching digest, and structured notes

#### Scenario: Artifact names change
- **WHEN** the versioned APK filename is used
- **THEN** its digest filename still follows the existing APK-name-plus-`.sha256` contract

### Requirement: Release-note outputs are validated before publication
The release workflow SHALL validate that generated Markdown is non-empty and that structured notes use a supported schema and match the current tag before publishing any release.

#### Scenario: Generated notes are valid
- **WHEN** both generated note outputs pass validation
- **THEN** the workflow continues through the existing build, signing, digest, and publication steps

#### Scenario: Structured notes disagree with the tag
- **WHEN** the generated structured document names a different tag from the release trigger
- **THEN** publication fails before a GitHub release is created

#### Scenario: User-facing metadata is missing but fallback is possible
- **WHEN** a pull request lacks user-facing prose but has a usable conventional title
- **THEN** validation succeeds with a warning because the deterministic fallback is valid
