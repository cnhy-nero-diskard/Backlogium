## Why

Personal Pace stays in its learning state until local history covers 28 completed dates, so a new
install waits a full month before the app will make any definitive feasibility claim. That wait is
the gate on collection deadline feasibility, the Home and Collections pacing detail, and — newly —
the gap-plan builder, which demands a one-off manual hours budget for as long as the profile is
learning. A month of nothing is a poor first month, and the 28-date figure was a conservative pick
rather than a derived one.

## What Changes

- Lower the reliable-profile threshold from at least 28 covered dates to at least 14, so a player
  who tracks consistently reaches definitive forecasts after two weeks instead of a month.
- Keep the six-active-dates floor exactly as it is. Over a 14-day span six active dates is a ~43%
  active rate, which is the requirement actually doing the work of rejecting a profile built from
  almost no play; halving the span without it would let three scattered sessions qualify.
- Not a breaking change: a profile that is reliable today stays reliable, every existing reliable
  profile keeps the same expected minutes, and no stored value changes. The threshold is evaluated
  on each derivation from session history, so the first derivation after the update reclassifies
  any qualifying profile with no migration and no recompute.

## Capabilities

### New Capabilities

<!-- None. This change adjusts an existing threshold. -->

### Modified Capabilities

- `personal-pace-forecasting`: the confidence-aware profile requirement changes its covered-date
  threshold from 28 to 14, along with the two scenarios that name that number.

## Impact

- `PersonalPace.RELIABLE_COVERED_DATES` — one constant, the sole place the threshold is expressed.
- `PersonalPaceTest` — the boundary assertions that pin reliable at 28 and learning at 27.
- Behaviour reaches every existing Personal Pace consumer without any of them changing: collection
  deadline feasibility and the `Change deadline` eligibility rule, the Home and Collections pacing
  detail, and the gap-plan builder's capacity provenance. Each already branches on
  `isReliable`, so each begins making definitive claims two weeks earlier.
- No schema change, no migration, no persisted state, and no network behaviour.
