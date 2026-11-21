## Why

Issue #157 reports an unexplained blank strip below Library content and inconsistent bottom navigation on setup and pushed screens. The strip still needs device reproduction, while the current route exception list makes navigation visibility easy to miss as screens are added.

## What Changes

- Show Backlogium's bottom navigation only on Home, Library, History, Analytics, and Settings overview; hide it on first-run configuration and every pushed screen.
- Reproduce and remove the Library bottom gap in list and both grid densities under gesture and three-button system navigation.
- Keep the last Library item reachable and preserve system Back and return-to-tab behavior across bar transitions.
- Verify representative screens in both themes and at increased font size.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `app-ui`: Add explicit app bottom-navigation visibility and bottom-spacing behavior for the Library and nested routes.

## Impact

- Covers issue #157 from feedback index #174. The Library visit state in `02-retain-library-visit-context` and HLTB reviewer work in `04-improve-hltb-review-flow` share navigation routes but have separate behavior contracts.
- Expected implementation areas: `BacklogiumAppRoot.kt`, route classification, `LibraryScreen.kt`, and affected child-screen inset handling.
- No persistence, database, or network contract change is expected.
