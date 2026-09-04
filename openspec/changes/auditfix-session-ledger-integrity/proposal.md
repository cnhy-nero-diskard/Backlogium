## Why

**Branch: `fix/auditfix-session-ledger-integrity`**

Four audit findings land on the one thing `CLAUDE.md` calls a load-bearing constraint rather
than a preference: **the on-device engine is the sole author of derived values.** Three are
confirmed correctness bugs at `severity/high`, and they share a property that makes them the
most urgent work in the audit — *the damage cannot be repaired afterwards.* Playtime totals
can be re-derived from Steam. Session boundaries, XP totals, and progress-event history
cannot; Steam exposes no history to reconcile against, so a bad row is bad permanently.

**#116 — concurrent presence checks create duplicate open sessions.** The presence path takes
no coordination at all. `PresenceSessionRecorder.onObservation` reads
`sessionDao.getAllOpenSessions()`, derives, and only then enters `SessionActionWriter` —
so two overlapping `checkNow()` calls (a recurring poll and an app-foreground check) can both
see no open session, both derive `Open`, and both commit. `Session.kt:28`'s index is
**deliberately non-unique**, with a documented backup/restore rationale, so nothing in the
schema stops the second insert. Afterwards, `firstOrNull`/`getOpenSession` return an
arbitrary one of the two: one row can stay orphaned open forever, session counts are
permanently doubled, and which duplicate later extensions land on depends on an unordered
query. This is precisely the "two independent session detectors produce records with
disagreeing boundaries that cannot be deduplicated" failure `CLAUDE.md` names.

**#115 — a clock rollback persists an inverted session.** `SessionDiffer` emits
`Extend(endAt = now)` with no out-of-order check and `SessionActionWriter` writes it verbatim,
so an open session at `startAt = 1000` whose next observation arrives with `now = 500`
becomes `startAt = 1000, endAt = 500`. A later no-delta poll reconstructs open state from
that rewound `endAt`, so closing the session preserves the impossible interval.
`PresenceSessionDeriver` already handles non-monotonic clock movement
(`.coerceAtLeast(current.minutes)` at `:117`); the owned-game path never got the same
treatment. Note the audit named `Extend`, but `Open(startAt = previousPollAt, endAt = now)`
in the same loop is exposed to the same rollback and needs the same guard.

**#114 — valid XP settings overflow total XP to zero.** `RuleField` validates a floor and no
ceiling (`RuleDraft.kt:81` checks only `parsed < field.minimum`), so `xpPerMinute =
2147483647` is accepted. `Gamification.gameXp` then evaluates `m * cfg.xpPerMinute` as `Int`
and wraps; `levelState`'s `coerceAtLeast(0)` turns the wrapped negative into `0`, and the
device persists `totalXp = 0, level = 1`. The clamp is what makes this silent — it converts
a detectable overflow into a plausible-looking value. The same unchecked `Int` accumulation
affects `games.sumOf { … }` and `achievementXp`, which can overflow across a large library
without any absurd setting at all.

**#104 — Family Shared removal is attributed as earned play.** Removing a shared game is user
bookkeeping: `FamilySharedGameRepository.kt:225-244` deletes that game's tracked state and
rewrites affected daily progress, then `:258-267` recomputes with `RecomputeSource.SYNC`.
`ProgressEventDetector.kt:40+` treats `SYNC` as the only event-producing provenance and
`RecomputeSource.kt:10-15` documents it as earned play — so a removal-induced level, streak,
or quest change can enter the progress-event protocol as though the player earned it.
`progress-events/spec.md:86-105` requires the opposite: non-earned changes emit nothing and
reseed the delivery baseline.

## What Changes

- **At most one open session per game, enforced where it cannot be raced past.** The
  read-derive-write sequence in the presence path stops being three separable steps.
  `design.md` Decision 1 picks the mechanism; the constraint is that it must hold without
  depending on the process-wide sync mutex, because `auditfix-background-work-contracts`
  narrows that mutex next and the presence path never took it anyway.
- **A stored session interval is never inverted.** Both owned-game emission sites (`Open` and
  `Extend`) reject or clamp an observation timestamp that precedes the interval it would
  extend, matching what `PresenceSessionDeriver` already does.
- **Derived totals cannot silently wrap or clamp.** XP accumulates in a width that cannot
  overflow from any accepted configuration, and `RuleField` gains a ceiling so absurd input
  is refused at entry rather than absorbed. `design.md` Decision 3 covers the persisted
  width and its migration.
- **Removal declares non-earned provenance.** A dedicated `RecomputeSource` for
  administrative removal, so the reseed-don't-emit path in `progress-events` applies.
- **BREAKING (behavioural, not schema)**: a player who has set `xpPerMinute` to an
  extreme value will see it refused on next edit, and their persisted `totalXp = 0` corrected
  on next recompute to the real accumulated value. That correction is a large upward jump in
  derived state and must reseed the baseline rather than fire a cascade of level-up events —
  see task 5.5.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `steam-sync`: a game has at most one open session; a synthesized session interval is never
  inverted
- `gamification`: derived totals cannot silently wrap or clamp
- `progress-events`: administrative removal of tracked history declares non-earned provenance

## Impact

| Path | Change |
|---|---|
| `data/repo/PresenceSessionRecorder.kt` | read-derive-write no longer racy (`:69`, `:87`) |
| `data/repo/SessionActionWriter.kt` | open-session guard at the write boundary (`:37`, `:74`) |
| `data/local/entity/Session.kt` | open-row uniqueness — **not** on the documented non-unique natural key |
| `data/local/dao/SessionDao.kt` | insert/upsert surface for the guard |
| `domain/SessionDiffer.kt` | out-of-order guard on `Open` and `Extend` (`:115-119`) |
| `gamification/…/Gamification.kt` | accumulation width (`:105`, `:160`, `:173`) |
| `ui/settings/RuleDraft.kt` | `RuleField` ceiling (`:80-81`) |
| `data/local/entity/PlayerProfile.kt` | `totalXp` width — **schema migration** |
| `domain/RecomputeSource.kt` | new non-earned source |
| `data/repo/FamilySharedGameRepository.kt` | removal recompute uses it (`:258-267`) |

**Depends on `auditfix-spec-truth`.** Two reasons, both concrete. Its `live-status` repair
(#106) removes the clause saying XP derives "solely from playtime-delta-synthesized
sessions" — this change writes requirements about presence-derived sessions and would
otherwise contradict live text. And its `MigrationTest.kt` extension must exist before this
change adds the `totalXp` migration, for exactly the reason the archived
`auditfix-sync-write-integrity` gave for the same dependency: *this app's data cannot be
re-derived.*

**Blocks `auditfix-background-work-contracts`.** That change narrows the
`SteamSyncCoordinator` mutex, whose own KDoc says it "serializes operations that read or
write the raw session/daily-progress ledger in this process." It is currently the only
cross-worker serialization of that ledger. Narrowing it before the session write is
independently safe turns #116's two-caller race into a five-caller one — `SteamSyncWorker`,
`ReconciliationWorker`, `PostPlaySyncWorker`, `AccountChangeCoordinator`, and
`DailyProgressBackfillUseCase` all take that lock today.

**Blocks `add-hidden-games`** (0/55 tasks), which plans its own non-earned provenance for
hide/unhide and will touch `RecomputeSource` and `ProgressEventDetector`. Landing the
removal source first sets the pattern in two files for that change to follow rather than
inventing a parallel one.

**Existing precedent to extend, not duplicate**: `WriteIntegrityDaoTest.kt:570` already
asserts that concurrent observations cannot double-count *without* using
`SteamSyncCoordinator`, and says so in a comment. That is the right test shape for #116 and
the right place for its regression case.

**Not addressed here**: the `SteamSyncCoordinator` scope itself
(`auditfix-background-work-contracts`), and whether `PlayerProfile` should be split into
per-domain tables — field-scoped writes already made the single-table design safe and no
finding demands it.
