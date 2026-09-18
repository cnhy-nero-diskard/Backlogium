## Context

Settings currently has a stateful ViewModel wrapper and one stateless composable that renders every section in a vertical scroll. Many operations have carefully designed busy states, validation, privacy disclosures, and confirmations; those behaviors must survive relocation. The app shell already supports pushed destinations, providing a precedent for detail screens without new top-level tabs.

## Goals / Non-Goals

**Goals:**

- Replace feature-by-feature scanning with four stable task destinations.
- Preserve one source of Settings state and every existing operation/guardrail.
- Give the overview useful status without leaking secrets or implementation vocabulary.
- Make Settings loading and back navigation predictable.

**Non-Goals:**

- Changing sync, cloud, backup, import, update, asset, hidden-game, Family Sharing, quest, or gamification behavior.
- Adding new settings values or removing expert controls.
- Adding more bottom-navigation items.

## Decisions

### 1. Introduce a nested Settings navigation graph

Use routes for `settings`, `settings/account-sync`, `settings/gameplay`, `settings/data-privacy`, and `settings/advanced`. The overview remains the bottom-nav destination. Detail routes are pushed surfaces with a title and back action; the bottom navigation is hidden while inside them, matching other pushed destinations.

Expanding four enormous sections inline was rejected because it retains one long page and weakens orientation. Four independent ViewModels were rejected because they would duplicate collectors and allow inconsistent busy state.

### 2. Share one route-scoped Settings state holder

Scope the existing `SettingsViewModel` to the parent Settings graph and pass slices of the same state/actions into stateless overview and detail composables. Extract current cards into files/components by group without moving business logic into navigation code.

### 3. Define group summaries as pure presentation models

Create four summary models containing title, plain-language status, optional attention severity, and destination. Derive them from existing state with strict privacy rules: never include SteamID, masked key, cloud token, reader URL, backup filenames, or diagnostic payloads on the overview.

Attention priority is deterministic: blocking/unconfigured or failed action; in-progress; action recommended; healthy. Healthy summaries stay quiet and do not enumerate maintenance commands.

### 4. Assign each existing card to exactly one owner

- Account & sync: Steam account, setup, sync/reconcile, updates.
- Gameplay: live monitor, daily quest, hidden games, manual/removed Family Shared games.
- Data & privacy: completion dataset/contribution, offline Steam assets, cloud presence/history re-file, Steam history import, backup/restore.
- Advanced & diagnostics: diagnostics entry and editable XP/level rule constants.

This mapping is exhaustive so no control is duplicated between detail screens.

### 5. Keep safety dialogs at the graph host

Dialogs and activity-result flows that can outlive a detail composable—backup import/export, mismatch confirmation, contribution disclosure, update launch—remain hosted at the Settings graph level. Detail screens raise intents through the shared action object.

### 6. Keep the overview stable while state resolves

Always render four rows. Before local state resolves, show neutral placeholder summaries and disable navigation only when the destination truly cannot render safely; otherwise details may open and show their own bounded state. During operations, update the relevant summary without scrolling or rebuilding the overview.

### 7. Localize and simplify labels at the edge

Move visible strings into resources. Overview copy names player tasks; technical labels remain available inside the relevant detail screen where context and help text can explain them.

## Risks / Trade-offs

- **[Risk] More routes increase navigation complexity.** → Use one nested graph, stable route constants, and explicit navigation tests for every group/back path.
- **[Risk] Relocation drops a control or guardrail.** → Build an exhaustive card-to-group inventory and parity tests before deleting the monolith.
- **[Risk] Shared state survives longer than intended.** → Scope it to the Settings graph, not the activity, and verify cancellation when leaving Settings entirely.
- **[Risk] Users need extra taps for rare actions.** → Accept one purposeful drill-in in exchange for a comprehensible overview; summaries route directly to the correct group.

## Migration Plan

No persisted-setting migration is required. First extract group composables behind current behavior, then add the nested graph and overview, move dialogs to the graph host, replace the old monolithic route, and verify full action parity. Rollback can restore the monolithic composition because repositories and state contracts stay unchanged.
