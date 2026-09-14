# Place playtime from recorded presence

*Phase 4 of the cloud poller integration. Phases 1–3 are `record-presence-coverage`,
`add-cloud-presence-reader` and `backfill-shared-game-presence`.*

## Why

A session synthesized by playtime diffing starts at `previousPollAt` — the last time the app
managed to sync. That is a good estimate when the app syncs every fifteen minutes and a bad one
when it does not, and `steam-sync` credits every minute of a session to the date it began.

So: phone dies Friday night, play Saturday and Sunday, sync Monday. Six hours arrive as one session
dated Friday. Saturday and Sunday record zero qualifying minutes, both quests go unmet, and the
streak breaks — for two days that were actually played. The XP is right. Everything indexed by date
is wrong.

This is a correctness defect in the progression system, not a precision shortfall. The cloud has
recorded when that play actually happened, to the minute, the whole time.

## What Changes

- **Observed intervals place the minutes the differ counted.** Steam's `playtime_forever` remains
  the sole authority on *how much* was played; the cloud record decides *where those minutes go*.
  The cloud can never add, remove, or invent a minute.
- **One delta can become several sessions**, on the days they happened, instead of one session on
  the day the app last looked.
- **Unchanged when it cannot help.** No cloud record for the window, no coverage, or a gap small
  enough that the existing estimate is already good — the sync behaves exactly as it does today.
- **A one-time historical sweep** re-files the sessions already recorded, opt-in and reversible,
  modelled on `playtime-backfill`.
- **Corrections are silent**, under the retroactive provenance phase 3 introduced. Past quests are
  re-evaluated and streaks recomputed without a cascade of celebrations.

**This does not change XP or levels.** Those derive from cumulative minutes per game, which are
already correct. What changes is which day each minute belongs to — and therefore quests, streaks,
History, Analytics, Personal Pace and gap-plan inputs.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `steam-sync`: session synthesis by playtime diffing may take its boundaries from an external
  record of observed presence where one covers the window. The diffed total remains authoritative
  and the attribution rule is unchanged — more sessions simply means more start dates.
- `cloud-presence-reader`: the rule that a read writes nothing derived narrows again to permit
  placement of diffed minutes, and gains the placement rules and the historical sweep.
- `app-settings`: the cloud section gains the one-time sweep, its reversal, and what each does.

## Impact

- **`app/domain/`** — a pure placement rule consuming phase 2's intervals and `SessionDiffer`'s
  deltas, emitting the same `SessionAction` vocabulary. `SessionDiffer` itself is untouched.
- **`PlaytimeObservationCommitter`** — the one path from an observed reading to stored sessions
  gains an optional placement input. Both existing observers commit through it, so both benefit
  without either learning about the cloud.
- **Daily progress** — corrected through the existing `dailyProgressCorrections` path, which already
  recomputes dates from the session ledger.
- **No schema change.** A placed session is an ordinary session.

## Non-goals

- **Changing XP, levels, or the taper.** Placement moves minutes between dates; it does not change
  any game's total.
- **Requiring the cloud.** Every behaviour here degrades to today's when no record is available.
- **Splitting a session's minutes across dates.** The attribution rule stands: a session's minutes
  belong to the date it began.
- **Family-shared games.** Phase 3 owns those, and they have no diffed total to place.
