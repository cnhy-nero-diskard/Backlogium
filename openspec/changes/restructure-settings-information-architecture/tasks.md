## 1. Inventory and presentation contracts

- [x] 1.1 Build an exhaustive mapping of every current Settings card, action, dialog, busy state, and test tag to exactly one of the four groups, and verify no existing operation is unmapped or duplicated.
- [x] 1.2 Add pure group-summary models and deterministic attention priority, and verify unit tests cover unconfigured, failed, in-progress, recommended-action, healthy, and privacy-redaction states.
- [x] 1.3 Split the stateless Settings content into four group composables without changing behavior, and verify existing Settings tests still pass before navigation replaces the monolith.

## 2. Nested Settings navigation

- [x] 2.1 Add stable routes for the Settings overview and four pushed detail destinations, and verify navigation tests cover every overview-to-detail and system/back-button return path.
- [x] 2.2 Scope one `SettingsViewModel` and action surface to the parent Settings graph, and verify operation state survives navigation between overview/detail but is disposed after leaving the Settings graph.
- [x] 2.3 Hide bottom navigation on Settings detail destinations while preserving the Settings selected state on return, and verify top-level back-stack/save-state behavior remains consistent with other pushed screens.

## 3. Overview and group screens

- [x] 3.1 Implement the four-row Settings overview with plain-language summaries, attention treatment, stable loading placeholders, and restored scroll state; verify semantics expose group name, status, and action.
- [x] 3.2 Assemble Account & sync and Gameplay detail screens from extracted controls, and verify credentials/setup/sync/update plus monitor/quest/hidden/shared-game operations retain enabled, busy, error, and confirmation behavior.
- [x] 3.3 Assemble Data & privacy and Advanced & diagnostics detail screens, and verify import/assets/contribution/cloud/backup plus diagnostics/rule operations retain disclosures, validation, destructive confirmation, and result handling.
- [x] 3.4 Keep cross-destination dialogs and activity-result flows at the graph host, and verify backup mismatch, contribution disclosure, export/import, update launch, and rule confirmation survive recomposition/navigation correctly.

## 4. Player-facing copy and resources

- [x] 4.1 Replace first-level technical labels with player-task summaries while retaining exact endpoint/token/dataset/re-file terminology only inside the appropriate detail context, and verify content tests reject sensitive identifiers in overview summaries.
- [x] 4.2 Move Settings overview/detail labels, quantities, statuses, and dates to default Android resources (no locale-qualified resources or translations), and verify a non-default-locale test asserts fallback default English copy with locale-aware formatting while preserving validation and stored values.

## 5. Verification

- [ ] 5.1 Expand `SettingsScreenTest` to cover all four destinations, overview loading/attention, advanced progressive disclosure, and action parity; run the focused connected suite when a device is available.
- [ ] 5.2 Run `./gradlew.bat :app:testDebugUnitTest --offline --no-daemon` and `./gradlew.bat :app:compileDebugKotlin --offline --no-daemon`, verifying repositories/workers require no behavior changes.
- [ ] 5.3 Run `openspec validate restructure-settings-information-architecture --strict` and `git diff --check`, and verify all sixteen former first-level sections remain reachable through exactly one group.
