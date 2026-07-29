## 1. Export format & serialization

- [x] 1.1 Define the backup JSON data classes (`BackupFile` v1: `formatVersion`, `exportedAt`,
      `identity`, `ruleConfig`, `games`, `achievements`, `sessions`, `dailyProgress`,
      `hltbData`, `librarySortPrefs`, `playerProfile`, `computed`) with kotlinx.serialization
      annotations
- [x] 1.2 Implement ISO-8601 timestamp (de)serialization (no epoch millis in the file)
- [x] 1.3 Implement the export mapper: Room entities + DataStore values → `BackupFile`,
      including the identity skeleton (appId+name, apiName+displayName) alongside derived
      fields
- [x] 1.4 Implement the `computed` layer generation by invoking `:gamification` against the
      raw layer + `ruleConfig` at export time (xpPerGame, xpTimeline)
- [x] 1.5 Add a unique constraint or reliable natural-key lookup for `Session`
      `(appId, startAt, endAt)` to support merge-by-natural-key later

## 2. Manual export/import (SAF)

- [x] 2.1 Implement "Export Backup" action: SAF `CreateDocument` flow, serialize `BackupFile`
      to the chosen location
- [x] 2.2 Implement "Import Backup" action: SAF `OpenDocument` flow, deserialize and validate
      `formatVersion`, reject unsupported/invalid files without modifying data
- [x] 2.3 Wire both actions into a new export/import repository used by both manual and
      snapshot-restore paths

## 3. Merge engine

- [x] 3.1 Implement natural-key upsert for `Session` (`appId, startAt, endAt`),
      `DailyProgress` (`date`), `HltbData` (`appId`), `Game.isGoal`/`backfillMinutes` (`appId`)
- [x] 3.2 Implement first-write-wins merge for `Achievement.snapshotPercent` keyed by
      `(appId, apiName)`, comparing existing vs. imported `unlockedAt` and retaining the
      earlier
- [x] 3.3 Implement post-merge aggregate recompute: call `GamificationUpdater`/`:gamification`
      over the merged raw data to derive `totalXp`, `level`, `currentStreak` — never write
      these fields directly from the imported file
- [x] 3.4 Implement `longestStreak` protection: persist
      `max(existing, imported, recomputed)`
- [x] 3.5 Implement SteamID64 mismatch detection: compare `identity.steamId64` against the
      signed-in account, surface a warning, but proceed on confirmation
- [x] 3.6 Unit tests: overlapping session import (no duplication), non-overlapping session
      import (backfill), snapshotPercent retained on conflict, longestStreak never decreases,
      aggregates always recomputed not trusted from file

## 4. Automatic rolling snapshots

- [x] 4.1 Implement app-private snapshot storage (write `BackupFile` JSON to internal storage,
      not via SAF)
- [x] 4.2 Hook snapshot write into `SteamSyncWorker`'s success callback
- [x] 4.3 Implement the throttle check (skip write if newest snapshot is younger than the
      configured interval)
- [x] 4.4 Implement retention enforcement (discard oldest snapshot(s) beyond the configured
      retention count)
- [x] 4.5 Add `autoSnapshotEnabled`, `snapshotRetentionCount`, `snapshotIntervalHours` to
      `SettingsDataStore`, with sensible defaults (on, 7, ~24h)

## 5. Settings UI — Data & Backup section

- [x] 5.1 Add the "Data & Backup" section to the Settings screen, below/alongside the existing
      history-import data section
- [x] 5.2 Add the auto-snapshot on/off toggle, retention count control, and snapshot interval
      control, wired to `SettingsDataStore`
- [x] 5.3 Add the snapshot list (most recent first, up to retention count) with per-entry
      timestamp and "Restore" action, wired to the merge engine (section 3)
- [x] 5.4 Add "Export Backup..." and "Import Backup..." buttons, wired to the SAF flows
      (section 2), always enabled regardless of the auto-snapshot toggle
- [x] 5.5 Add the cross-account mismatch warning dialog shown before a mismatched import
      proceeds

## 6. Verification

- [ ] 6.1 Manual test: export, wipe app data (or fresh install), import — history, XP, level,
      streaks, goal tags, and HLTB matches are restored
- [ ] 6.2 Manual test: import a backup on top of existing data with partial overlap — no XP
      double-counting, gaps are backfilled, overlaps are replaced
- [ ] 6.3 Manual test: auto-snapshot writes at most once per configured interval across
      multiple syncs, and the snapshot list never exceeds the configured retention count
- [ ] 6.4 Manual test: import a backup with a different SteamID64 — warning shown, import
      proceeds only on confirmation
- [x] 6.5 Confirm exported JSON is legible without app source access: sensible field names,
      ISO-8601 timestamps, game/achievement names present alongside IDs
