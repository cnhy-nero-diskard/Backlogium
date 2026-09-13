## Context

See `proposal.md` for motivation. The threshold itself is trivial — one private constant compared
against `coveredDates` at the end of the pure derivation, with no stored state behind it. What
earns a design note is the second-order effect: the reliability threshold is also, in practice, the
*minimum sample the weekday model will ever be asked to work from*, and the two halves of that
model degrade very differently as the sample shrinks.

`coveredDates` is the span from the first observation to yesterday with interior gaps filled as
zero-minute dates, not a count of days with data. So at the new minimum the profile has exactly 14
dated observations, and each of the seven weekdays has exactly two.

## Goals / Non-Goals

**Goals:**

- Reach definitive forecasts after two weeks of tracking instead of four.
- Keep the change confined to classification, so no existing reliable profile's numbers move.
- State, rather than discover later, how the weekday model behaves at the new floor.

**Non-Goals:**

- Changing the forecast arithmetic, the 56-day lookback, or the 28-day recency half-life.
- Relaxing the six-active-dates floor (see Decision 2).
- Fixing the weekday frequency coarseness identified below — that is a real follow-up, deliberately
  not bundled here (see Decision 3).

## Decisions

### 1. Move only the covered-date threshold, 28 → 14

The constant gates `PersonalPaceConfidence` and nothing else. It is not an input to
`expectedActiveDays` or `expectedGamingMinutes`, so a profile that was already reliable produces
byte-identical forecasts afterwards, and a profile that newly qualifies produces exactly the
forecast it was already computing while labelled learning. There is no migration, no recompute, and
no persisted confidence value: `derive` runs from session rows on every collection, so the first
derivation after the update reclassifies.

Rejected: making the threshold configurable. Personal Pace deliberately has one derivation shared
by Home, Collections, and the gap-plan builder so those surfaces cannot disagree; a per-surface or
user-tunable threshold reintroduces exactly the divergence that design avoids.

### 2. Hold the active-date floor at six

Six active dates is what actually rejects a profile assembled from almost nothing, and it gets
*stricter* as the span shortens: six active days within 14 is a ~43% active rate, against ~21%
within 28. Halving the span therefore tightens the overall bar on density even as it loosens the
bar on elapsed time, which is the intended trade — reach a verdict sooner, but only for someone
who has actually been playing.

Rejected: scaling the floor down with the span (to three). It would make a profile reliable from
three scattered sessions in a fortnight, which is the case the confidence state exists to refuse.

### 3. Accept coarser weekday *frequency* at the floor, and record why it is the sharp edge

The weekday model has two halves and only one of them is protected against a thin sample:

- `typicalActiveDayMinutes` **is** blended toward the global active-day figure, weighted by
  `weekdayActive.size / 4`. At 14 covered dates a weekday has at most two active observations, so
  the blend is at most 0.5 and usually 0 or 0.25 — duration lands near the global value. This is
  the documented sparse-weekday blend working exactly as intended.
- `activeProbability` is **not** blended. It is the recency-weighted fraction of that weekday's own
  observations that were active, so from a two-observation sample it can only land near 0, 0.5, or
  1.

Since a date's contribution is `activeProbability * typicalActiveDayMinutes`, a weekday that
happened to catch no play in its two-sample contributes *zero* expected minutes for every one of
its occurrences in the forecast range. With six active dates spread across 14, two to four weekdays
plausibly land at zero, and a 60-day forecast then writes off roughly a sixth to a third of its
days. The profile is reliable by the new rule while its capacity figure is materially understated.

This is accepted rather than fixed, for two reasons. It is not new behaviour — the same arithmetic
runs today, just never on a sample this thin — and its error has a safe direction: understating
capacity makes a deadline look harder and a gap plan smaller. A false "at risk" is a bad outcome,
but it is a much better one than false confidence about a deadline the player will actually miss.

Rejected for now: blending `activeProbability` toward the global active-day probability on the same
sparse-weekday rule that already governs duration. It is the obvious fix and probably the right
one, but it changes the numbers for *every* profile including reliable ones, which is a different
change with a different blast radius than moving a classification threshold. If the false-alarm
rate proves noticeable in practice, that is the follow-up.

## Risks / Trade-offs

- [A 14-date profile understates capacity when some weekday caught no play, because weekday
  frequency is unblended] → Accept and record. The error direction is conservative, the arithmetic
  is unchanged, and the mitigation is scoped as a separate change rather than smuggled in here.
- [A player who tracks for two weeks then stops gets a definitive claim from stale history] → Not
  introduced by this change and not made worse by it: the 56-day lookback and the 28-day recency
  half-life are untouched, and a profile whose observations age out returns to learning on its own.
- [Reliability arrives while the player is mid-way through reading a gap plan, changing what the
  builder asks for] → No effect on an open result. The gap-plan snapshot is generated once from
  inputs read at generation time and is held stable until the player explicitly regenerates.

## Migration Plan

None. No schema, no stored state, no persisted confidence value. The constant changes, and the next
derivation from existing session rows reclassifies any profile that already qualifies. Rollback is
restoring the constant and the spec threshold; a profile reliable under 14 and not under 28 simply
returns to learning, which every consumer already handles as its pre-change state.
