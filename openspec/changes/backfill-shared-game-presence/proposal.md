# Recover family-shared play from cloud presence

*Phase 3 of the cloud poller integration. Phases 1–2 are `record-presence-coverage` and
`add-cloud-presence-reader`.*

## Why

A family-shared game has no `playtime_forever` to diff, so its sessions come from observed presence
— and the only observer is a foreground service that dies with the app. `game-sources` states the
consequence plainly today: play while the app is neither foregrounded nor monitoring produces no
session, and the game's tracked time is unchanged. Not delayed. Gone.

The cloud has been observing that same presence every minute since August. For borrowed games this
is not a refinement of something already recorded, it is the difference between a record and
nothing at all — and it is the one case where the cloud is the *only* possible source.

This is also the smaller of the two ingests, and the safer one to build first: it touches only
games the playtime differ never sees, so no session it writes can overlap one the differ authored.

## What Changes

- **Cloud intervals for family-shared games become sessions**, through the same deriver and the same
  writer the live observer already uses. One mechanism, a second observation source — not a second
  detector.
- **Owned games are untouched.** The ingest is handed an app id only when the stored source is
  family-shared, exactly as the live path already works.
- **An ingest position prevents double-counting** where the live observer already recorded the same
  stretch of play.
- **Coverage is respected.** Nothing bounds a shared game's total the way Steam's totals bound an
  owned game's, so an interval of unknown or partial coverage is clamped to what was actually
  confirmed rather than credited in full.
- **A new non-earned provenance** for play observed retroactively: derived values are corrected,
  baselines reseeded, and no celebration fires for a weekend already lived.
- **The disclosure changes.** Shared-game time is still "observed, not total", but the remedy
  offered alongside it now includes cloud presence.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `cloud-presence-reader`: the rule that a read writes nothing derived narrows to permit exactly one
  path — family-shared sessions through the existing presence mechanism — and gains the ingest's
  own rules: partition, position, coverage clamping, and idempotency.
- `game-sources`: presence-derived sessions may originate from either the on-device observer or the
  cloud record. The mechanism and the partition are unchanged; the set of observers is not.
- `progress-events`: play observed retroactively declares a provenance of its own, distinct from
  both earned-through-play and administrative bookkeeping.

## Impact

- **`app/domain/`** — the ingest rule, pure, consuming phase 2's reconstruction and emitting the
  same `SessionAction` vocabulary `SessionDiffer` and `PresenceSessionDeriver` already emit.
- **`app/data/`** — writes through the existing `SessionActionWriter`; a stored ingest position
  beside the read position.
- **`RecomputeSource`** — one new value, alongside `GAME_REMOVAL` and `VISIBILITY_CHANGE`.
- **No new session table, no schema change.** A recovered session is an ordinary session.
- **No owned-game behaviour change.** `SessionDiffer` and the sync path are untouched.

## Non-goals

- **Owned games.** Re-placing their minutes is phase 4 and has a different safety argument, because
  Steam's totals bound the error there and nothing bounds it here.
- **Recovering play the cloud never saw.** A shared game played entirely inside a poller gap stays
  unrecorded, and the disclosure continues to say so.
- **Changing what a shared game's tracked time claims to be.** It remains observed time, never a
  Steam-verified total.
