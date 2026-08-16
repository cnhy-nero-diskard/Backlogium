# release-packaging

## ADDED Requirements

### Requirement: Release version metadata is derived from the release tag
A distributable build SHALL take its version name and version code from the validated
release tag that triggered it, so that no two releases can share a version and later
releases always order above earlier ones. The version SHALL NOT be stored in a
committed build file where it could diverge from the tag.

#### Scenario: Tagged release build
- **WHEN** a release build is produced from a validated `vX.Y.Z` tag
- **THEN** the build's version name is `X.Y.Z` and its version code is a value strictly
  greater than that of any earlier `vX.Y.Z` tag

#### Scenario: Two consecutive releases
- **WHEN** `v1.4.2` is released and later `v1.5.0` is released
- **THEN** the second build's version code is greater than the first's, so an installed
  `v1.4.2` can be upgraded in place by `v1.5.0`

#### Scenario: Local build with no tag
- **WHEN** a build is produced locally with no version supplied
- **THEN** the build succeeds and carries a version name that identifies it as a
  development build rather than any released version

#### Scenario: Version is not committed
- **WHEN** the repository is inspected at any commit
- **THEN** no released version number is recorded in a build file that a tag could
  contradict

#### Scenario: A tag outside the encoding's range is refused
- **WHEN** a release tag carries a version component the version-code encoding cannot represent
  without colliding with another valid version
- **THEN** the release fails with a message naming the offending component, rather than
  producing a build whose version code duplicates or undercuts an earlier release

#### Scenario: Ordering holds across a major increment
- **WHEN** the highest version representable at one major number is compared with the first
  version of the next major number
- **THEN** the later version's code is greater, with no value of minor or patch able to reverse
  that

### Requirement: Release artifacts contain no developer credentials
A release build SHALL contain no Steam API key and no SteamID, regardless of whether
the machine assembling it has developer credentials configured locally. Credential
values SHALL be supplied to debug builds only, and the release variant SHALL pin them
to empty values explicitly rather than relying on their absence.

#### Scenario: Release built on a configured developer machine
- **WHEN** a release build is assembled on a machine whose local configuration contains
  a Steam API key and SteamID
- **THEN** the resulting artifact contains neither value

#### Scenario: Debug build on a configured developer machine
- **WHEN** a debug build is assembled on that same machine
- **THEN** the credential values are available to the build as before, so local
  development is unaffected

#### Scenario: Credential fields remain resolvable in release
- **WHEN** release code reads the build-time credential fields
- **THEN** the fields exist and resolve to empty values, so the credential seed path
  compiles and runs without special-casing the variant

#### Scenario: A populated local configuration does not fail the build
- **WHEN** a developer assembles a release locally while credentials are configured
- **THEN** the build succeeds and produces a credential-free artifact rather than
  reporting an error
