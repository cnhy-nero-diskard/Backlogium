# Proposal

## Why

Favorites, recent play and collection membership shortcuts have shipped. Deadline collections still need a way to leave active views while retaining their outcome and evidence. [Issue #168](https://github.com/cnhy-nero-diskard/Backlogium/issues/168)

## What Changes

- Offer an explicit, one-way Finish and archive action after the deadline arrives.
- Store an immutable deadline outcome with goal, members, metrics and uncertainty. Make archives readable and available to backup and later insights.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `custom-collections` — Eligibility, evidence and immutable deadline outcome.
- `app-ui` — Archive confirmation and read-only archive access.
- `backup-restore` — Compatible archival snapshot import and export.

## Impact

Android collections, Room, backups and archive reads. Builds on `01-streamline-collection-access` and the existing deadline calculation contract.
