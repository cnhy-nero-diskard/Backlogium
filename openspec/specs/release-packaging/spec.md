# release-packaging

## Purpose

Defines what a published release of the app carries beyond the installable artifact itself, so
that a client consuming the release (see `app-updates`) can verify what it downloaded without
trusting the transfer.

## Requirements

### Requirement: A release publishes a digest for its artifact
A published release SHALL carry a cryptographic digest of its installable artifact, generated in the
same job from the same file that is uploaded, so that a client can verify a download without
trusting the transfer. The digest SHALL be published as its own asset of the same release.

#### Scenario: Digest published with the artifact
- **WHEN** a release is published
- **THEN** it carries both the installable artifact and a digest asset covering exactly that file

#### Scenario: Digest matches the uploaded artifact
- **WHEN** a published digest is compared against the artifact published beside it
- **THEN** they match, because both were produced from the same file in the same job

#### Scenario: Digest generation fails
- **WHEN** the digest cannot be produced
- **THEN** the release fails rather than publishing an artifact no client can verify

#### Scenario: Digest is integrity, not authenticity
- **WHEN** the digest is used by a client
- **THEN** authenticity still rests on the artifact's signing key, which the digest does not and
  cannot establish
