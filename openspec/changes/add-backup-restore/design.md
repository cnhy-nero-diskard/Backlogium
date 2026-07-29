## Context

Backlogium's local Room database (v5, `exportSchema = false`) and two DataStore files hold two
distinct kinds of data:

- **Steam-fetchable cache**: `Game.name/iconUrl/playtimeForever/playtime2Weeks`,
  `Achievement.unlocked/unlockedAt/globalPercent` (live), `PlayerProfile.steamLevel/personaName/
  avatarUrl`. Re-derivable from `GetOwnedGames`/`GetPlayerAchievements`/`GetSteamLevel` at any
  time.
- **App-derived, irreplaceable**: `Session[]` (the entire History timeline, synthesized by
  diffing playtime — Steam exposes no session concept at all), `DailyProgress[]` (streak/quest
  source of truth), `PlayerProfile.totalXp/level/currentStreak/longestStreak/
  playtimeBackfilled`, `Game.isGoal`/`backfillMinutes`, `Achievement.snapshotPercent` (rarity %
  frozen at first unlock — deliberately diverges from Steam's live `globalPercent` forever and
  already drives earned XP; the single most irreplaceable value in the app), `HltbData[]`
  (third-party HowLongToBeat matches, not Steam), `RuleConfig` (10 gamification tunables), and
  `LibrarySortPrefs`.

`RuleConfig` changes retroactively recompute the player's entire history (see `app-settings`
spec: "Rule changes disclose their retroactive effect"). This means a derived value like
"XP contributed by game X" is not a fixed historical fact — it's a function of raw ingredients
(minutes played, `snapshotPercent`) and whichever `RuleConfig` is active. Any export that reports
XP numbers must make its `RuleConfig` inputs explicit, or those numbers become unreproducible
and misleading once tunables change.

`longestStreak` is already a protected high-water mark under recompute (see `app-settings`
spec: "Longest streak is never lowered by a recompute"). Any merge/restore path must preserve
that same invariant, not just the normal recompute path.

The API key lives in `EncryptedCredentialStore`, encrypted with an Android Keystore key that is
device-bound by design; the store's own code comments already acknowledge this key does not
survive a restore to a new device. This change does not attempt to change that.

There is no existing export/import/backup code in the repo today (confirmed via search) — this
is greenfield. Android's stock Auto Backup is enabled in the manifest but left fully
unconfigured (empty `backup_rules.xml`/`data_extraction_rules.xml`); this change does not rely
on or configure Auto Backup — it is a fully in-app, explicit mechanism instead.

## Goals / Non-Goals

**Goals:**
- Make the app-derived data (history, XP/streaks, rarity snapshots, HLTB matches, goal tags,
  rule config) recoverable after reinstall/device migration.
- Produce a single JSON file that is self-contained (restorable without requiring a live Steam
  sync first) and legible to both a human and an LLM agent reading it directly — named fields,
  ISO-8601 timestamps, no bare opaque IDs.
- Support both a manual, user-directed export/import (via SAF) and an automatic, app-managed
  rolling snapshot (crash/corruption safety net).
- Guarantee that importing/restoring never double-counts XP and never regresses the
  `longestStreak` or `Achievement.snapshotPercent` invariants that already hold elsewhere in the
  app.

**Non-Goals:**
- No cloud/remote backup destination — local file export (SAF) and local app-private snapshots
  only.
- No encryption of the export file itself — it carries no secrets (no API key), only a public
  SteamID64 plus derived gameplay data.
- No fix for Room's `exportSchema = false` — noted as a related pre-existing gap, not addressed
  here (the export format's own `formatVersion` is deliberately independent of Room's internal
  schema versioning so this change doesn't need to depend on fixing it).
- No per-game notes/tags/ratings/reviews — confirmed not to exist in the codebase; not
  introduced by this change.
- No change to `:gamification`'s pure functions — this change only invokes them (to produce the
  export-time computed rollup and to recompute aggregates after merge).

## Decisions

### 1. Format: single versioned JSON, three layers, uncompressed

```
{
  "formatVersion": 1,
  "exportedAt": "<ISO-8601>",
  "identity": { "steamId64": "..." },
  "ruleConfig": { ... },                 // layer 2
  "games": [ { appId, name, isGoal, backfillMinutes } ],       // layer 1 (skeleton + derived)
  "achievements": [ { appId, apiName, displayName,
                       snapshotPercent, unlockedAt } ],         // layer 1 (skeleton + derived)
  "sessions": [ { appId, startAt, endAt, minutes } ],           // layer 1
  "dailyProgress": [ { date, minutesPlayed, questMet } ],       // layer 1
  "hltbData": [ { appId, mainStory, ..., matchStatus } ],       // layer 1
  "librarySortPrefs": { focus, library },                      // layer 1
  "playerProfile": { totalXp, level, currentStreak,
                      longestStreak, playtimeBackfilled },     // layer 1
  "computed": { "xpPerGame": [...], "xpTimeline": [...] }       // layer 3, export-time only
}
```

Chosen over a raw SQLite file copy or a gzipped bundle: plain JSON is diffable, human- and
LLM-legible without tooling, and decouples the backup format's evolution from Room's internal
schema (`formatVersion` vs. Room's own migration version). File size is not a concern at the
scale of a single player's history.

**Why include a Game/Achievement identity skeleton (`appId`+`name`, `apiName`+`displayName`)
even though it's technically Steam-fetchable?** Two reasons: (a) `Session`/`DailyProgress`/
`Achievement.snapshotPercent` all key off `appId`/`apiName` — without the skeleton, restoring
onto a fresh install would require a live Steam sync to run first just to satisfy foreign-key
targets, and the file would not be self-contained; (b) a bare numeric `appId` is meaningless to
an LLM agent reading the file without a name attached, which directly serves the stated
LLM-readability goal.

**Why a separate `computed` layer, generated fresh at export time and never re-imported
verbatim?** Because XP-per-game is `f(raw ingredients, RuleConfig)`, not a stored fact. Treating
it as authoritative input on import would let a backup taken under an old `RuleConfig` silently
inject stale XP numbers after the user has since changed their rules. Regenerating it — both at
export time (for legibility) and again after any import/merge (so the app's own displayed
values always match what the current raw data + current `RuleConfig` actually produce) — keeps
it honest.

### 2. Merge semantics: natural-key upsert, never blind replace, never additive sum

| Data | Natural key | Merge rule |
|---|---|---|
| `Session` | `(appId, startAt, endAt)` | Upsert |
| `DailyProgress` | `date` | Upsert |
| `HltbData` | `appId` | Upsert |
| `Game.isGoal`, `Game.backfillMinutes` | `appId` | Upsert |
| `Achievement.snapshotPercent` | `(appId, apiName)` | First-write-wins (see below) |
| `longestStreak` | n/a (single value) | High-water mark (see below) |
| `totalXp`, `level`, `currentStreak` | n/a | Always recomputed post-merge, never trusted from file |

`Session`'s existing Room primary key is an autogenerated int, not stable across
install/export/import — the upsert must key on `(appId, startAt, endAt)` instead of the PK.
Implementation should confirm this tuple is a reliable natural key in practice (e.g. add a
unique index) rather than relying on DAO-level dedup alone.

**Why upsert instead of full-table replace?** The user explicitly wants "backfill" support:
importing a file that has data the current DB is missing (e.g. restoring onto a partially-synced
fresh install) without discarding anything the current DB already has that the file doesn't.
Natural-key upsert gives both replace-on-conflict and backfill-on-gap in one rule.

**Why never trust imported aggregates?** `totalXp`/`level`/`currentStreak` are sums/derivations
over `Session`/`DailyProgress`/`Achievement` data. If both the raw rows AND the aggregate were
imported and merged independently, a raw-row upsert followed by blindly writing the imported
aggregate risks double-counting (e.g. importing sessions that were already partially reflected
in the existing `totalXp`). Always recomputing aggregates from the post-merge raw data via the
existing `GamificationUpdater`/`:gamification` engine eliminates this class of bug by
construction — there is no code path where an aggregate is ever taken as ground truth from a
file.

**`longestStreak` special case:** protected as `max(existing-before-import, imported-value,
freshly-recomputed-after-merge)`. This mirrors the existing `app-settings` invariant
("Longest streak is never lowered by a recompute") — a restore must not be a backdoor around a
protection the app already enforces for ordinary rule changes.

**`Achievement.snapshotPercent` special case:** first-write-wins, keyed by the earliest non-null
`unlockedAt` between the existing row and the imported row. `snapshotPercent` is defined
elsewhere in the app as frozen forever at first unlock; an import must not be able to overwrite
an already-frozen value, even if the imported file is "newer" as a file.

### 3. Identity verification: warn, don't block, on SteamID mismatch

The export's `identity.steamId64` is compared against the currently signed-in account at import
time. A mismatch triggers a clear warning but does not block the import. Rationale: the
SteamID is not a security boundary (it's public, and the API key is never in the file), so
blocking would only get in the way of legitimate cases — migrating between one's own alt
accounts, or restoring before onboarding has fully completed on a new device.

### 4. Two independent trigger mechanisms, sharing one merge engine

- **Manual export/import** (SAF-based, user-chosen file location) is always available,
  independent of the auto-snapshot toggle.
- **Automatic rolling snapshot** is app-private (no SAF prompt, not user-visible as a file),
  triggered by `SteamSyncWorker`'s success callback, throttled to a configurable interval
  (default ~1/day) so it doesn't write on every sync (sync can run as often as every 15 minutes
  per the `steam-sync` spec), and retains a configurable number of most-recent snapshots
  (default 7). Restoring a snapshot runs through the exact same merge logic as manual import —
  there is only one import/merge code path, with two entry points (a picked file, or a selected
  snapshot from the Settings list).

**Why throttle at write-time rather than at trigger-time (e.g. a separate scheduled job)?**
Piggybacking on the existing sync worker's success path avoids introducing a second scheduler;
the throttle check ("is the newest snapshot younger than the configured interval?") is a cheap
guard at the point of writing, not a new periodic work request.

### 5. Settings surface

A new "Data & Backup" section is added to the existing Settings screen (extending the
`app-settings` capability), alongside the existing history-import data section:
- Auto-snapshot on/off toggle
- Retention count (adjustable, default 7)
- Snapshot interval (adjustable, default ~1/day)
- A list of current snapshots (up to the retention count) with timestamps and a per-entry
  "Restore" action
- "Export Backup..." / "Import Backup..." buttons (always present, independent of the toggle)

## Risks / Trade-offs

- **[Risk]** `Session`'s natural key `(appId, startAt, endAt)` could theoretically collide for
  two genuinely distinct sessions with identical boundaries. → **Mitigation**: extremely
  unlikely given minute-granularity timestamps across a single account's play history; if it
  becomes a concern during implementation, a stable UUID assigned at session-creation time is a
  low-cost addition.
- **[Risk]** Room's `exportSchema = false` means there's no tracked schema history to validate
  the export format against as Room migrations happen in the future. → **Mitigation**: the
  export format's `formatVersion` is deliberately decoupled from Room's schema version, so
  format evolution is handled independently; this is called out as a related gap but not
  blocking for this change.
- **[Risk]** A large/old backup imported long after `RuleConfig` has changed multiple times
  could produce a `computed` rollup in the file that looks stale next to the app's current
  numbers. → **Mitigation**: the `computed` layer is documented as export-time-only and is
  always regenerated after import, so the app's own UI never displays stale computed values;
  only the raw file itself (if inspected directly by a human/LLM) could show numbers that don't
  match current live values, and it carries the `ruleConfig` snapshot needed to explain why.
- **[Trade-off]** Warn-not-block on SteamID mismatch means a user could accidentally merge a
  different account's history into their own if they dismiss the warning without reading it. →
  Accepted: the alternative (hard block) removes legitimate device-migration/alt-account flows,
  and there is nothing to "gain" by cheating Backlogium's own gamification numbers, so the
  downside of an accidental merge is a data-quality inconvenience, not a security issue.

## Open Questions

- Should the Session natural key be strengthened with a stable UUID column now, or is
  `(appId, startAt, endAt)` sufficient at implementation time? (See Risk above — leaning toward
  starting without it and revisiting if collisions are observed.)
