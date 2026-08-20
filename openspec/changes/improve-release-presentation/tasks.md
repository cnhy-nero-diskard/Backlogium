## 1. Release-Note Authoring and Composition

- [ ] 1.1 Add a pull-request template section for short user-facing release-note bullets or the literal `None`, with examples that avoid implementation jargon.
- [ ] 1.2 Define the versioned structured release-note schema, section ordering, item/count/length bounds, and product-repository URL constraints in a dependency-free composer module.
- [ ] 1.3 Implement parsing of pull-request release-note metadata, explicit `None`, and conventional-title fallbacks for legacy or incomplete pull requests.
- [ ] 1.4 Implement deterministic classification of user-facing and technical entries, including maintenance-only and same-commit release states.
- [ ] 1.5 Generate polished GitHub Markdown and structured JSON from the same in-memory release model, including linked technical details and an explicit full-changelog URL.
- [ ] 1.6 Add composer fixtures and automated tests for features, fixes, performance changes, internal-only changes, missing metadata, malformed inputs, bounded output, and same-commit tags.

## 2. Release Workflow Presentation

- [ ] 2.1 Give the release workflow run and its gate/publication jobs descriptive tag-oriented display names without changing semver or master-containment eligibility.
- [ ] 2.2 Resolve the previous published valid semver release explicitly and collect the comparison's merged pull-request metadata for the composer.
- [ ] 2.3 Invoke and validate the composer before publication, emitting fallback warnings while failing on empty, malformed, unsupported, or tag-mismatched generated outputs.
- [ ] 2.4 Rename the collected APK, matching `.sha256` digest, and structured-notes document to versioned `Backlogium-<version>` filenames while preserving the digest lookup contract.
- [ ] 2.5 Publish `Backlogium <version>` with the generated Markdown `body_path` and all versioned assets, retaining the existing tests, signing secrets, build arguments, and publish permissions.
- [ ] 2.6 Add a dry-run workflow/composer check that proves a representative tag produces readable Markdown, valid structured notes, and the expected asset paths without creating a release.

## 3. Structured Notes in Update Discovery

- [ ] 3.1 Add serializable structured-note DTOs and domain models with explicit schema/tag validation, bounded section/item content, and a validated optional full-changelog URL.
- [ ] 3.2 Recognize the versioned structured-notes asset independently of APK and checksum selection, and implement a size-limited notes download through the update API layer.
- [ ] 3.3 Enhance otherwise valid update offers with structured notes while ensuring absent, failed, oversized, unsupported, malformed, or tag-mismatched notes never suppress the update.
- [ ] 3.4 Implement a bounded legacy-body sanitizer that removes GitHub Markdown decoration, conventional prefixes, contributor suffixes, and standalone changelog URLs without executing markup.
- [ ] 3.5 Persist the structured presentation and legacy fallback through optional update-state keys so discovered updates remain reviewable offline without destructive migration.
- [ ] 3.6 Extend repository, mapping, and DataStore tests across successful structured notes and every non-gating fallback path, including proof that note data cannot alter artifact verification or version selection.

## 4. Native Update Presentation

- [ ] 4.1 Redesign the update sheet content hierarchy around the product/version heading, installed-to-available transition, categorized bullet sections, and maintenance/no-details states.
- [ ] 4.2 Add a full-changelog action only for the validated repository HTTPS comparison URL and keep technical pull-request details out of the in-app summary.
- [ ] 4.3 Preserve Later, Update, Cancel, progress, verification, permission, installation, and failure states within the redesigned scrollable sheet.
- [ ] 4.4 Change update notifications to show the available version and a bounded first user-facing item or maintenance summary instead of the complete release body.
- [ ] 4.5 Add Compose semantics tests for structured, maintenance, sanitized legacy, long/scrolling, downloading, and failure presentations, including absence of raw Markdown and URLs.

## 5. Verification and Release Acceptance

- [ ] 5.1 Run the composer test suite and inspect generated Markdown/JSON for recent feature, internal-only, and same-commit release fixtures.
- [ ] 5.2 Validate release workflow syntax, script parsing, structured-note schema checks, expected versioned filenames, and repository formatting with no publication side effects.
- [ ] 5.3 Run focused update repository, persistence, notification, and Compose tests, then run the standard offline Android unit-test suites and `git diff --check`.
- [ ] 5.4 Verify the actual update-review path on a connected device or emulator for structured and maintenance notes, recording screenshots and preserving explicit evidence if device verification is unavailable.
- [ ] 5.5 For the first explicitly authorized tagged release, verify the terminal Actions result, GitHub title/body/assets, concise notification, readable in-app sheet, APK digest, signer verification, and successful installation before marking rollout complete.
