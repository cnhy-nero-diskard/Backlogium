# Settings tab

## Why

Home is doing two jobs. Five of its eight blocks are the dashboard the app exists for — now
playing, level/XP, today's quest, streak — and three are administration: the Steam account card,
the one-time history import, and the sync row. `add-steam-profile-header` already named the split
without acting on it, calling the account card's contents "settings concerns, not identity."

There is also configuration the app persists but has never been able to change. `RuleConfig`
carries every gamification constant — `xpPerMinute`, `levelBase`, `questThresholdMin`,
`questMode`, `streakGraceDays`, and the per-rarity achievement awards — and
`SettingsRepository.setRuleConfig()` **has no caller anywhere in the app**. The write path exists,
the storage exists, the engine reads it on every recompute; only a surface is missing. A settings
destination is what makes tunable rules actually tunable.

## What Changes

- A fourth top-level destination, **Settings**, added to the bottom navigation alongside Home,
  Library, and History.
- The **Steam account card**, the **Steam history import/reset**, and the **sync row**
  (`Last sync` + `Sync now`) move off Home and into Settings. Home keeps only the sync *error*
  card, which gains an inline **Retry** so a failed sync still has a recovery action on screen.
- **Rule configuration becomes editable** for the first time: daily quest goal, quest mode, and
  streak grace as primary controls; XP per minute, level base, and per-tier achievement XP behind
  a collapsed **Advanced** section.
- **Consequence guardrails on every rule change.** `GamificationUpdater.recompute()` is stateless
  — it rebuilds total XP, every stored day's `questMet`, and both streaks from raw inputs under
  the current config. So *every* knob is retroactive, not just the advanced ones: raising the
  daily quest goal re-evaluates the player's entire history. Saving a rule change presents the
  concrete before/after effect and requires confirmation, matching the pattern the history import
  already uses.
- **Rule changes trigger an immediate recompute.** `recompute()` currently has only two callers
  (`SteamSyncWorker`, `PlaytimeBackfillUseCase`), so without this a saved setting would leave Home
  showing a stale level and streak until the next 15-minute poll.
- **BREAKING (behavioral):** `longestStreak` becomes a persisted high-water mark. Today
  `GamificationUpdater` derives it as `maxOf(pastStreak.longest, currentStreak)` and overwrites the
  stored value, so a recompute under a stricter config permanently erases an earned record. Making
  rule editing available turns that latent bug into a routine one, so it is fixed here rather than
  deferred. "Longest streak" changes meaning from *longest under current rules* to *longest ever
  achieved*.
- A **sync indicator** on the trailing edge of the profile header, replacing the spinner that
  lived inside the Home "Sync now" button. It reflects **all** syncs — periodic as well as manual
  — so the shell carries a genuine "talking to Steam" cue rather than one that only reacts to a
  button now two taps deep. It respects reduced-motion settings and latches briefly visible so a
  fast sync does not just twitch.

## Capabilities

### New Capabilities
- `app-settings`: a dedicated settings destination — its sections, the rule controls it exposes,
  the retroactivity guardrails on saving them, and the relocation of account, sync, and data
  controls off Home.

### Modified Capabilities
- `app-ui`: the shell gains a fourth navigation destination; the Home screen requirement narrows
  to the dashboard (account card, history import, and sync controls removed; error card gains
  Retry); the profile header gains a sync indicator.
- `gamification`: `longestStreak` becomes a monotonic high-water mark rather than a value derived
  fresh on each recompute.

## Impact

- **Affected code (new):** `ui/settings/` (screen + view model); a `Destination.SETTINGS` entry;
  a sync-indicator composable in the profile header.
- **Affected code (modified):** `Destination.kt`; `BacklogiumAppRoot` (route + nav item);
  `HomeScreen`/`HomeViewModel` (three blocks removed, Retry added); `ProfileHeader` /
  `ProfileHeaderViewModel` (3-flow → 4-flow combine); `SyncScheduler.syncInProgress` (widened to
  cover the periodic work); `GamificationUpdater` (high-water streak); `PlayerProfile` semantics
  for `longestStreak`.
- **Open design question, deferred to design.md:** where the post-save `recompute()` call is
  owned. `SettingsRepository` is deliberately storage-only and has no path to the domain layer;
  a `SettingsViewModel` calling `GamificationUpdater` directly may be the cleaner seam.
- **No new network calls, no schema migration.** Every setting already has a DataStore key, and
  `longestStreak` already exists on `PlayerProfile`.

## Non-goals

- **Theme or appearance settings.** `app-ui` specifies a single hand-authored dark scheme with no
  dynamic color, deliberately identical across devices. There is nothing to toggle.
- **Sync cadence configuration.** The 15-minute period is a WorkManager constraint interacting
  with Steam rate limits, not a user preference; exposing it invites states the app has never
  been tested in.
- **Migrating the library/history sort keys.** `enhance-library` plans to persist those in
  `SettingsDataStore`, but they are view state belonging to their own screens, not settings-screen
  entries.
- **Credential editing in place.** The account card keeps delegating to the existing onboarding
  flow; Settings changes where the card lives, not how it works.
- **Restoring a `longestStreak` already destroyed by a prior recompute.** The high-water fix is
  forward-only — there is no history to recover a lost record from.
