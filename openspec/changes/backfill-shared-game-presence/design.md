## Context

See proposal.md — Why.

What already exists and is reused unchanged:

- **`PresenceSessionDeriver`** is pure and takes one observation at a time, folding it into an open
  session with a gap tolerance. It emits `SessionDiffer.SessionAction` values, so a derived session
  is indistinguishable downstream from a diffed one.
- **`SessionActionWriter`** persists those actions. Both existing mechanisms go through it.
- **The partition is enforced by wiring, not by choice.** `GameDao.ownedGamesForDiffing` feeds the
  differ; `PresenceSessionRecorder` passes an app id to the deriver only when the stored source is
  family-shared, and an owned game arrives as `null` exactly as "not in a game" does.
- **Phase 2's reconstruction** produces intervals with coverage, and writes nothing.

The asymmetry that shapes every decision below: an owned game's error is bounded by
`playtime_forever`, which Steam will report correctly on the next sync regardless of what the
ingest did. A shared game has no such total. Whatever this phase credits *is* that game's tracked
time, and no source exists to contradict it later.

## Goals / Non-Goals

**Goals:**

- Recover shared-game play that is currently lost permanently, without creating a second detector.
- Never credit time no observer confirmed.
- Reseed derived values silently rather than celebrating a weekend already lived.

**Non-Goals:**

- Owned games. Phase 4, different safety argument.
- Improving the on-device observer, or changing when it runs.
- Any new session shape, table, or writer.

## Decisions

### Feed the existing deriver rather than write sessions directly

Cloud intervals are expanded back into observations and folded through `PresenceSessionDeriver`,
rather than converted straight into session rows.

*Why:* the deriver already encodes decisions this ingest would otherwise have to re-make and
re-justify — when silence closes a session, what a backwards clock means, how a zero-minute launch
is treated, that `endAt` stays current so the tolerance is measured from something real. Writing
rows directly would duplicate that reasoning in a second place, and the two would drift. Feeding
the deriver means the cloud is a second *observer*, not a second *mechanism*, which is exactly the
distinction `game-sources` now draws.

*Alternative considered:* map each interval to one session row. Simpler, and wrong in a specific
way — an interval is not a session. Two intervals separated by less than the gap tolerance are one
session by the rule the on-device path already applies, and a direct mapping would fragment
history that the live observer would have merged.

### Clamp to confirmed observation, and let the deriver see the clamp

An interval observed only until a stated time is fed as observations up to that time and no
further. An interval carrying the raw interior-gap pair excludes that interior span the same way,
by the ingest's own tolerance applied to the raw pair. An interval of unknown coverage is treated
conservatively rather than as continuous.

*Why:* nothing bounds the error here. This is the same reasoning phase 1 used to record coverage in
the first place, arriving at the point where it finally has to be acted on. Crediting an
unconfirmed tail would put minutes into a game's permanent total on the strength of an assumption,
and no later sync would correct it.

*Consequence, accepted:* a poller outage during shared-game play costs recorded minutes. That is
the right direction to be wrong in — the disclosure already says this figure is observed time, not
a total, and under-recording is consistent with that claim while over-recording contradicts it.

*Alternative considered:* credit the full interval and mark it low-confidence. Rejected — the
confidence marking would have to be carried through XP, quests, streaks and analytics to mean
anything, and none of those have a notion of a provisional minute.

### One ingest position, discarded with the read position

A stored position records how far cloud observations have been folded in. It is cleared on a
configured account change, alongside phase 2's read position.

*Why a position rather than deduplicating against stored sessions:* overlap between a cloud
interval and a live-observed session is a boundary question, and boundary questions between two
observers are exactly what cannot be resolved by equality. A monotonic position sidesteps it: an
observation is folded in once, ever. The `(appId, startAt, endAt)` index that backs the backup
merge engine's natural-key lookup remains available as a backstop, but it is not the primary
guard.

### Introduce the retroactive provenance here, not in phase 4

`RecomputeSource` gains one value for play observed after the fact, used by this phase and by
phase 4.

*Why here:* this phase is the smaller of the two ingests and its blast radius is one game source.
Introducing the provenance where it can be verified against a narrow change — rather than alongside
phase 4's re-placement of the whole ledger — is the cheaper place to get it wrong.

*Why a new value rather than reusing `BACKFILL`:* `BACKFILL` means the one-time import of Steam's
historical totals, which is a baseline reseed from a number the player never watched accumulate.
This is play the app genuinely observed, just late. `progress-events` already requires that a
reader tracing a reseed can tell a removal from a hide; the same argument applies here.

*The honest tension, recorded:* this play *was* earned, and filing it under a non-earned provenance
is a deliberate choice about the event vocabulary rather than a claim about the play. It is written
into the spec's rationale rather than left implicit, because a future reader will otherwise read it
as a category error.

## Risks / Trade-offs

- **Double-crediting where the live observer and the cloud both saw the play** → The ingest position
  is the primary guard; the deriver's own gap tolerance means adjacent observations from either
  observer merge rather than fragment. Verified by test over an overlap fixture, which is the case
  most likely to be got wrong.

- **An owned game reaching the deriver** → Enforced by wiring, as it already is for the live path:
  the ingest is handed an app id only for a presence-derived source. A test asserts an owned-game
  interval produces no action at all.

- **Under-recording feels like a bug to the user** → Mitigated by the disclosure already required
  for shared games, which states the figure is observed time and points at the settings that would
  improve coverage. This phase widens that remedy rather than silently changing the number.

- **The five weeks of pre-phase-1 history are all unknown-coverage** → They are treated
  conservatively, which for shared games means most of that era contributes little. That is the
  correct outcome, not a shortfall to engineer around: for those intervals nothing anywhere records
  whether the poller was watching.

- **A recovered session lands on a date whose quest was already evaluated** → Handled by the same
  re-evaluation path a sync already uses for past dates, under the new provenance so the correction
  is silent.

## Migration Plan

No schema change. Recovered sessions are ordinary rows in the existing table.

**Rollback:** the ingest is reversible in the same sense a backfill is — the position is clearable
and the sessions it wrote are identifiable by the ingest that authored them, so clearing returns
derived values to what they were. Removing the cloud configuration stops further ingest without
touching what was already recovered, which is the behaviour a user removing a data source expects.

**Sequencing:** ships after phase 2 is live and a read has been verified against real data, because
the ingest consumes phase 2's reconstruction directly.

## Open Questions

- Whether the gap tolerance used when folding cloud observations should match the on-device
  constant or be tighter, given cloud observations arrive at a one-minute cadence rather than
  thirty seconds. Both are defensible, the difference only affects where two nearby stretches merge,
  and it can be settled with real intervals in hand without changing any requirement or task.
