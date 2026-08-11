## Why

Backlogium currently has no way to protect or move the data that matters most: the app's own
derived state. Nearly everything meaningful to a player — session history, streaks, XP/level,
frozen achievement-rarity snapshots, HLTB matches, goal tags, and the gamification rule
configuration itself — exists only in this app's local Room database and DataStore files. None
of it can be recovered from Steam's API on a reinstall or device migration; only the raw
game/achievement cache (name, icon, playtime, live unlock percentage) can be re-fetched. A
single reinstall today permanently erases a player's entire history, XP, and streaks with no
recovery path.

## What Changes

- Add a JSON export/import format covering the app's derived data (session history, daily
  progress, player XP/level/streaks, goal tags, backfill offsets, frozen achievement-rarity
  snapshots, HLTB matches, gamification rule configuration, library sort preferences), plus a
  minimal Steam game/achievement identity skeleton so the file is self-contained and legible to
  both humans and LLM agents (ISO-8601 timestamps, named fields, no opaque IDs).
- Add a manual export flow: user picks a destination via Android's Storage Access Framework and
  the app writes a single versioned JSON file there.
- Add a manual import flow: user picks a JSON file via SAF; its contents are merged into the
  local database using natural-key upsert (not blind table replace, not additive sums), with
  `longestStreak` and `Achievement.snapshotPercent` protected from regression per their existing
  invariants, and all aggregate values (XP, level, streaks) recomputed from the merged raw data
  rather than trusted verbatim from the file.
- Add an automatic rolling snapshot mechanism: app-private (not user-visible) JSON snapshots
  written after a successful Steam sync, throttled to a configurable interval, retaining a
  configurable number of most-recent snapshots.
- Add a "Data & Backup" section to the existing Settings screen: auto-snapshot toggle, retention
  count, snapshot interval, a list of current snapshots each with a "Restore" action, and
  "Export Backup..." / "Import Backup..." buttons.
- On import, if the backup's SteamID64 does not match the currently signed-in account, warn the
  user clearly but do not block the import.

## Capabilities

### New Capabilities
- `backup-restore`: Export/import format, manual export/import via SAF, automatic rolling
  snapshots, and the merge/recompute semantics that reconcile imported data with existing local
  state.

### Modified Capabilities
- `app-settings`: Adds a "Data & Backup" section to the Settings screen (auto-snapshot toggle,
  retention count, snapshot interval, snapshot list with restore actions, manual export/import
  buttons), alongside the existing data section (history import/reset).

## Impact

- **`:app` module only** — `data/local/` (Room entities: `Game`, `Achievement`,
  `PlayerProfile`, `Session`, `DailyProgress`, `HltbData`) and `data/local/SettingsDataStore.kt`
  (`RuleConfig`, `LibrarySortPrefs`) become the read/write surface for a new export/import
  repository. New SAF integration for file picking. New snapshot trigger hooked off
  `SteamSyncWorker`'s success path. New Settings UI section and its ViewModel/use-cases.
- **`:gamification` module** — invoked, not modified. Its pure functions produce the
  export-time computed rollup (XP per game, XP timeline) and recompute aggregates after any
  import/restore.
- **`data/credentials/EncryptedCredentialStore.kt`** — explicitly untouched. The Steam Web API
  key is never included in an export; only the public SteamID64 is, for verify-on-import.
- **Room schema** — no entity changes required; this change only adds serialization/merge logic
  on top of the existing v5 schema. `exportSchema = false` remains a related pre-existing gap,
  noted in design.md, not addressed by this change.
