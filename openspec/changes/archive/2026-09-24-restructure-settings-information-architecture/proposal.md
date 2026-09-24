## Why

Settings currently presents sixteen similarly weighted sections in one long scroll, mixing routine account actions with datasets, cloud infrastructure, backups, diagnostics, and advanced rules. A task-oriented hierarchy is needed so ordinary users can understand status and reach common actions without reading an administration console.

## What Changes

- Replace the monolithic Settings stack with four top-level groups: Account & sync, Gameplay, Data & privacy, and Advanced & diagnostics.
- Present each group as a concise summary row with current status and a clear next action, then open its detailed controls on a pushed Settings sub-destination.
- Place setup, Steam account, sync, and update controls under Account & sync; quests, live monitor, Focus-related rules, and hidden/shared-game management under Gameplay; imports, offline assets, completion-data contribution, cloud presence, and backup/restore under Data & privacy; diagnostics and rule constants under Advanced & diagnostics.
- Replace first-level implementation vocabulary with player-facing labels while retaining precise detail and warnings inside the relevant sub-screen.
- Preserve every existing action, busy/disabled state, disclosure, validation rule, and destructive confirmation.
- Render a stable loading summary rather than an empty surface and move Settings copy into Android string resources.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `app-settings`: Change Settings navigation, grouping, summaries, terminology, loading behavior, and reachability requirements while preserving the existing settings operations.

## Impact

- Affects `ui/settings/SettingsScreen.kt`, Settings navigation/routes in the app shell, Settings state/presentation models, string resources, and Settings Compose tests.
- Existing repositories, workers, DataStore preferences, backup/cloud behavior, and destructive-operation contracts remain unchanged.
- The new sub-destinations remain inside Settings and do not add top-level navigation items.
