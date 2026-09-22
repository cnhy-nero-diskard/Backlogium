## 1. Unified filter behavior

- [x] 1.1 Introduce the immutable Library filter model and derivation helpers, and verify unit tests cover query, genre, coverage, Family Shared, combined filtering, and preservation of density/sort preferences.
- [x] 1.2 Derive active-filter chips, individual clear actions, clear-all behavior, and empty-result reasons from that model, and verify Family Shared-only and every combined-filter no-result case has truthful recovery.
- [x] 1.3 Move filter state to the appropriate screen/ViewModel owner while retaining reset-on-leave behavior, and verify leaving/re-entering Library clears only transient filters and selection.

## 2. Discovery-first controls

- [x] 2.1 Replace the crowded two-row toolbar with a search-first bar, labeled filter entry/count, and visible active-filter chips, and verify search remains mounted and clearable for every no-result state.
- [x] 2.2 Add the labeled Library tools surface for density and HLTB start/force actions while leaving section-local sort controls with their sections, and verify all prior controls remain reachable with their enabled/busy states intact.
- [x] 2.3 Keep HLTB batch progress and pending-review attention visible in the main flow, and verify an active refresh can still be stopped and a queued review can be opened without entering Library tools.

## 3. Scalable genres and accessible selection

- [x] 3.1 Replace the all-chip genre sheet with a searchable lazy list and selection summary, and verify searching never drops hidden selections and dismissing applies the same any-genre matching rule.
- [x] 3.2 Add a visible `Select games` entry point while retaining long press as an accelerator, and verify both list and grid densities can enter, toggle, and exit selection mode.
- [x] 3.3 Add long-click labels, selected-state semantics, and named toggle actions to game cards, and verify focused Compose tests expose correct semantics in ordinary and selection modes.

## 4. Localized presentation

- [x] 4.1 Move Library controls, statuses, filter recovery, selection quantities, and dialogs to default Android resources (no locale-qualified resources or translations), and verify single/plural selection plus combined-filter copy assert fallback default English text with locale-aware formatting under a non-default locale.
- [x] 4.2 Verify no resource migration changes HLTB candidate identity, Focus membership copy, Family Sharing provenance, or accessibility-expanded XP wording.

## 5. Verification

- [x] 5.1 Run `./gradlew.bat :app:testDebugUnitTest --offline --no-daemon` and verify all Library filter/presentation regressions pass.
- [ ] 5.2 Run `./gradlew.bat :app:compileDebugKotlin --offline --no-daemon` and focused Library connected Compose tests when a device is available; debug Kotlin and Android-test compilation pass, but the connected device rejected test APK installation with `INSTALL_FAILED_USER_RESTRICTED` before any tests ran.
- [x] 5.3 Run `openspec validate streamline-library-discovery-controls --strict` and `git diff --check`, and verify the visual-regression change remains the sole owner of screenshot infrastructure/goldens; no screenshot infrastructure or golden files changed here.
