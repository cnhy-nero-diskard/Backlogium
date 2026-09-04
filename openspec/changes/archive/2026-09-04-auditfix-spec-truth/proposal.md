## Why

**Branch: `fix/auditfix-spec-truth`**

The spec-drift audit (#98) filed nineteen findings. In seven of them the audit's own
conclusion was that *the implementation is correct and the spec is stale* — the normative
text either contradicts itself, contradicts a newer capability, or lost a material clause
during an archive merge. Under `CLAUDE.md` `openspec/specs/` is the source of truth for
behaviour, so a stale clause is not a cosmetic wart: it is the repository asserting
something false about the product, and every later change reads it to decide what is
normative.

Two of the seven are actively load-bearing for the audit fixes that follow:

- `live-status/spec.md:194-197` says XP, quests and streaks are derived **"solely from
  playtime-delta-synthesized sessions."** `game-sources/spec.md:89-104` requires the exact
  opposite for Family Shared games, and `PresenceSessionRecorder.kt:150` implements the
  `game-sources` version. Any change reasoning about presence-derived session provenance —
  `auditfix-session-ledger-integrity` does, twice — is reading a spec that denies those
  sessions exist.
- `live-status/spec.md:135-151` and `:153-171` state incompatible requirements about idle
  monitoring. One says the service keeps polling while idle when Live monitor is enabled;
  the other says no background observation runs while the player is not in a game. Both
  cannot hold, and `PresenceService.kt:143-155` picks the first.

The remaining five are honest-text repairs with no behavioural component at all
(#100, #101, #102, #103, #113).

Folded in alongside them is one verification gap (#121) that belongs to the same
capability as #101 and must land before any later change adds a migration:
`MigrationTest.kt:37-54` builds a populated v13 fixture but runs only `MIGRATION_13_14`
and validates v14, while `BacklogiumDatabase.kt:73` is at **v26**. The composed
v13→v26 path that a real device would take is not exercised anywhere. The archived
`2026-08-14-auditfix-verification-coverage/design.md:33-37` explicitly designed that
fixture to run to *current*; it stopped tracking current after v14.

## What Changes

- **`live-status`**: narrow the "Not observing while idle" scenario to the
  Live-monitor-disabled (default) case, so it no longer contradicts the opt-in idle
  monitoring requirement (#105). Drop "solely from playtime-delta-synthesized sessions"
  from the XP-exclusion scenario, keeping the useful distinction that the *transient live
  timer* is not an XP input while allowing stored sessions to originate from presence
  (#106).
- **`steam-sync`**: restore the bounded-recent-game-window clause to "Fetch is scoped to
  one game" (#113). "Scoped" means only the stopped app can be attributed and request cost
  is independent of library size — not that Steam returns exactly one row. The archived
  `2026-08-27-add-post-play-sync/design.md` Decision 1 chose `GetRecentlyPlayedGames` with
  a bounded window deliberately; the consolidated spec lost the clause.
- **`game-sources`**: reversing a Family Shared removal restores the game immediately
  rather than on next observed play (#103), matching `app-settings/spec.md:449-452` and
  `FamilySharedGameRepository.kt:278-289`.
- **`onboarding-credentials`**: retire "Repeatable credential editing from Home" and
  replace it with the Settings-based equivalent (#102). `app-ui/spec.md:190-206` already
  requires the account card to be *absent* from Home.
- **`schema-migration`**: distinguish destructive loss from an explicitly designed,
  mechanically verified semantic migration or repair (#101). Two shipped, tested migrations
  (`MIGRATION_13_14`, `MIGRATION_17_18`) intentionally violate the current absolute
  wording; reverting them to preserve known-wrong representations would make the product
  less correct. Also require the deep-history fixture to run the composed chain to the
  current database version, and extend it from v14 to v26 (#121).
- **`backup-restore`**: define the exported rule configuration as export-only
  reproducibility metadata that import never applies (#100), matching
  `BackupFile.kt:13-15` and `BackupRepository.kt:118-119`.

**Not a behaviour change.** Every spec edit here moves the normative text onto the shipped
behaviour, which the audit independently judged correct in each case. The only code in this
change is test code (#121).

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `live-status`: idle-monitor contradiction resolved; XP-input clause no longer excludes
  presence-derived sessions
- `steam-sync`: targeted-fetch scoping clause restored to the bounded-window contract
- `game-sources`: reversing a shared-game removal is immediate
- `onboarding-credentials`: repeatable credential editing lives in Settings, not Home
- `schema-migration`: designed semantic migrations permitted; chain verification must run
  to the current version
- `backup-restore`: rule configuration is export-only metadata

## Impact

| Path | Change |
|---|---|
| `openspec/specs/live-status/spec.md` | two requirements reworded (via delta + archive sync) |
| `openspec/specs/steam-sync/spec.md` | one scenario reworded |
| `openspec/specs/game-sources/spec.md` | one scenario reworded |
| `openspec/specs/onboarding-credentials/spec.md` | one requirement retired, one added |
| `openspec/specs/schema-migration/spec.md` | two requirements reworded |
| `openspec/specs/backup-restore/spec.md` | one requirement reworded, one added |
| `app/src/androidTest/.../MigrationTest.kt` | deep-history fixture extended v14 → current |

**Purpose lines are not delta-editable.** `backup-restore`'s `## Purpose` lists "rules"
among the data this capability backs up *and restores*. A delta cannot change a Purpose, so
task 6.4 narrows it by hand in the main spec at archive time. This is the one place where
this change touches `openspec/specs/` outside the sync.

**Lands first.** This change is a prerequisite for `auditfix-session-ledger-integrity`
(needs the `live-status` XP clause honest before it can write presence-session deltas),
`auditfix-background-work-contracts` (touches `steam-sync` behaviour whose scoping clause is
currently wrong), and `auditfix-settings-boundary` (its stale-Home copy fix is the same
story as #102). Its `MigrationTest.kt` extension must precede any later change that adds a
migration — `auditfix-session-ledger-integrity` is expected to add one.

**Not addressed here**: the spec-drift findings where the audit concluded the *code* is
wrong (#99, #104, #107, #109, #111, #112) — those live in the changes that fix the code, and
#110, where the specification itself needs redesign rather than repair
(`auditfix-collections-editor`).
