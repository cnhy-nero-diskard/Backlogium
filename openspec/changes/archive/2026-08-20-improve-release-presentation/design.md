## Context

The tag-triggered release workflow currently asks GitHub to generate a release title and body, uploads a generically named APK plus digest, and publishes immediately. GitHub renders that body acceptably as Markdown, but its contents remain developer-oriented. The updater copies the same body into `AvailableUpdate`, persists it, and displays it through plain Compose `Text` and notification `BigTextStyle`, exposing Markdown syntax, contributor handles, and URLs to users.

The change crosses GitHub Actions, repository contribution conventions, release assets, updater networking and persistence, notifications, and Compose UI. It must remain compatible in both directions: existing app versions must still install new releases, and a new app version must still present older releases that have no structured notes.

## Goals / Non-Goals

**Goals:**

- Produce one deterministic release model and derive both GitHub Markdown and app-facing structured notes from it.
- Separate user-visible changes from linked technical details without hiding the latter from repository visitors.
- Make release runs, releases, and downloadable artifacts recognizable by product name and version.
- Present update notes natively in Compose and concisely in notifications without executing arbitrary Markdown or HTML.
- Preserve the existing release eligibility, signing, digest, update-channel, and installation guarantees.

**Non-Goals:**

- Replacing semantic-version tags, GitHub Releases, or `softprops/action-gh-release`.
- Generating marketing prose with an AI service or requiring a new external changelog service.
- Retrofitting historical GitHub releases.
- Treating release-note metadata as an input to artifact trust, version ordering, or installation decisions.
- Redesigning the complete Settings screen or update installation flow.

## Decisions

### 1. Pull requests carry explicit user-facing release-note metadata

The pull-request template will include a `Release note` section asking for short user-facing bullets or the literal `None`. The release composer will prefer these entries. `None` places the PR only in technical details.

For merged PRs created before the field exists or left incomplete, the composer will remain non-blocking: it will classify conventional prefixes (`feat`, `fix`, and `perf`) and use a cleaned PR title as a user-facing fallback, while `docs`, `test`, `chore`, `ci`, `build`, and unrecognized entries remain technical. The workflow will emit a warning when a normally user-visible category required this fallback.

This is preferred over GitHub's `.github/release.yml` categorization because that mechanism depends on labels and the repository's recent PRs do not carry them. It is also preferred over tag-message authoring because the PR is where the change still has enough context to describe its user impact.

### 2. A dependency-free deterministic composer owns the release model

A repository script runnable on the Ubuntu release runner will consume the previous release tag, current tag, and merged-PR metadata. It will use only checked-in code and platform-provided runtime libraries, producing stable output suitable for unit tests.

The model will contain:

- a schema version and release tag;
- ordered user-facing sections for features, fixes, performance, and maintenance;
- plain-text items with bounded lengths;
- linked technical PR details for the GitHub renderer; and
- the explicit previous-tag comparison URL when one exists.

The previous comparison boundary will be the latest earlier published valid semver release in the same history, supplied explicitly rather than left to GitHub's automatic selection. If the two tags identify the same commit, the model will state that there are no application changes instead of repeating notes from an older range. If there are only internal changes, it will state that the release is maintenance-only.

Alternative considered: render GitHub Markdown directly in Compose. Rejected because it preserves developer-oriented wording, makes notifications difficult to summarize, and introduces parsing/rendering complexity without establishing a stable app contract.

### 3. Publish Markdown and versioned JSON from the same model

The composer will write a polished Markdown body and a small UTF-8 JSON document. The JSON asset will use a versioned product filename such as `Backlogium-1.8.0-release-notes.json`; the APK and digest will similarly use `Backlogium-1.8.0.apk` and `Backlogium-1.8.0.apk.sha256`.

The GitHub release title will be `Backlogium <version>`. Its body will show user-facing sections first, place PR-linked technical details in a collapsible section, and finish with a named full-changelog link. `softprops/action-gh-release` will receive the generated Markdown through `body_path` and upload all three assets.

Alternative considered: embed machine-readable markers in the Markdown body. Rejected because parsing a presentation document into an app contract is brittle and makes harmless Markdown edits capable of breaking the client.

### 4. Improve Actions presentation without changing its gates

The workflow will set a tag-oriented `run-name`, and the gate and publication jobs will have readable display names. Existing validation, tests, signing, digest generation, and publish ordering remain intact. The workflow will validate that both note outputs match the current tag and are non-empty before publishing.

Release-note composition errors will fail before publication because publishing mutually inconsistent assets would create a permanent bad release. Missing per-PR user prose alone is not a composition error and uses the documented fallback.

### 5. Structured notes enhance discovery but never gate an update

Update discovery will recognize the structured-notes asset by its versioned suffix, download it with a small fixed response-size limit, and accept only the supported schema whose tag matches the GitHub release. Parsed strings remain plain text; markup and HTML are neither interpreted nor executed.

If the notes asset is absent, unavailable, oversized, malformed, unsupported, or mismatched, the repository will still offer an otherwise valid APK. It will derive a bounded legacy presentation from the release body by removing Markdown decoration, contributor suffixes, and standalone changelog URLs. Failure to enhance presentation will not change update cadence, notification eligibility, artifact verification, or installation.

The structured model will be persisted with the existing available-update state so an already-discovered update remains reviewable offline. Optional keys and legacy fallback avoid a destructive DataStore migration.

### 6. Compose and notifications use purpose-built projections

The update sheet will show a product/version heading, the installed-to-available version transition, categorized bullet rows, and an honest maintenance/no-details message when appropriate. Technical PR links will remain on GitHub rather than filling the app sheet. A full-changelog action may open only the validated HTTPS URL produced for this repository.

The update notification will use the version as its title and the first user-facing item, or the maintenance fallback, as bounded plain-text body content. It will not place the complete release document into `BigTextStyle`.

No Markdown rendering dependency is required. The UI will be testable through structured semantics and will retain the current Later, Update, Cancel, progress, verification, and error behavior.

## Risks / Trade-offs

- [PR authors omit useful user-facing notes] -> Keep releases non-blocking, warn on cleaned-title fallback, and make the template instruction explicit.
- [Generated JSON and Markdown drift] -> Generate both in one script from one in-memory model and validate tag/schema before upload.
- [A malformed or unexpectedly large note payload consumes resources] -> Enforce a small download limit, schema validation, bounded section/item counts, and bounded text lengths.
- [Untrusted PR text introduces active content] -> JSON-escape generation, render app content as plain text, and constrain external navigation to the expected HTTPS repository URL.
- [Old and new app/release combinations differ] -> Old apps continue reading the human-readable GitHub body; new apps fall back when the structured asset cannot be used.
- [More release assets add visual weight] -> Use descriptive filenames and keep technical details collapsed so the release page remains scannable.

## Migration Plan

1. Add composer fixtures/tests and the PR template field without changing published releases.
2. Update the release workflow presentation, versioned artifact names, generated body, structured asset, and validation.
3. Add structured-note consumption, persistence, sanitization fallback, notification projection, and native Compose presentation.
4. Verify the composer locally with feature, fix, maintenance-only, missing-metadata, and same-commit fixtures; then run focused updater/UI tests and the existing release workflow checks.
5. Publish the first release under the new contract and verify its Actions run, GitHub page, assets, notification, and on-device update sheet.

Rollback consists of returning the workflow to GitHub-generated notes and generic assets. The app-side fallback remains valid and requires no state reset.

## Open Questions

None. The design deliberately uses a warning plus deterministic fallback, rather than blocking a release solely because an older or incomplete PR lacks user-facing prose.
