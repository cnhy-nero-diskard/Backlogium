## Why

Backlogium's release pipeline reliably tests, signs, and publishes updates, but it exposes GitHub's developer-oriented generated Markdown directly to users. Release pages are difficult to scan, Actions runs and assets have generic names, and the in-app update sheet and notification show raw headings, formatting marks, contributor handles, and full URLs instead of a readable product summary.

## What Changes

- Introduce one deterministic release-note composition step that turns merged-PR metadata into both polished GitHub Markdown and versioned structured notes for Backlogium.
- Add a short user-facing release-note field to the pull-request template, with an explicit no-user-visible-change marker and a conventional-title fallback for existing PRs.
- Present release notes by user-facing category, keep linked technical details available on GitHub, and describe maintenance or same-commit rebuild releases without repeating stale notes.
- Give workflow runs, jobs, releases, APKs, checksums, and structured-note assets descriptive, versioned names.
- Render structured release notes as native Compose headings and bullets in the update sheet, and use a concise user-facing summary in update notifications.
- Preserve update availability when structured notes are absent or malformed by falling back to a sanitized legacy release body.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `release-packaging`: Published releases gain a deterministic, audience-aware release-note package and descriptive presentation metadata alongside the existing verified APK and digest.
- `app-updates`: Update discovery, notifications, and the review sheet gain structured, readable release-note presentation with a safe legacy fallback.

## Impact

- Affects `.github/workflows/release.yml`, the pull-request template, release-note generation/validation scripts, and release asset naming.
- Extends the GitHub release contract with a small versioned structured-notes asset while retaining the existing APK-plus-digest contract.
- Affects update DTOs, repository mapping and persistence, notification copy, and the Compose update sheet.
- Requires focused unit tests for note composition/parsing and Compose UI tests for readable update states; no changes to signing trust, semver eligibility, installation, or update cadence are intended.
