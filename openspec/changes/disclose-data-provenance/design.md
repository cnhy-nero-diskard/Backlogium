## Context

The app has exactly one honest statement about derivation, and it lives in a KDoc comment on a
private composable:

```kotlin
// HistoryScreen.kt:453
/** "~12:43 AM · 23m played" ... Deliberately an approximate *start*, not a ... */
```

Everything else presents derived and observed values identically. The mechanism producing the
difference is in `SessionDiffer`:

```kotlin
actions += SessionAction.Open(
    appId = poll.appId,
    startAt = previousPollAt,   // ← the estimate
    endAt = now,
    minutes = delta,            // ← Steam's own number
)
```

One line of that constructor is measured and one is guessed, and the same row carries both. Any
figure reading `minutes` inherits Steam's accuracy; any figure reading `startAt`, or counting rows,
inherits the poll cadence.

The cadence is not fixed either. `steam-sync` sets a **15-minute minimum** for the periodic worker,
but WorkManager defers under Doze, so the real gap between polls ranges from 15 minutes to hours.
The error is therefore unbounded above and cannot be quoted as a tolerance.

```
   what a figure is made of
   ────────────────────────

   Session.minutes ──────────▶ daily chart, most-played, quest totals
        Steam's counter            amount exact, day boundary fuzzy

   Session.startAt ──────────▶ time-of-day pattern, session start times
        previousPollAt             wholly an estimate

   row count over startAt ───▶ session count, avg length, longest
        poll-gap runs              a "session" is a unit the app invented
```

## Goals / Non-Goals

**Goals:**

- Make each figure state what it is made of, where it is read.
- Distinguish figures whose amounts are exact from figures whose boundaries are invented, rather
  than hedging both equally.
- Say the mechanism once, in one place, reachable from anywhere it applies.
- Keep every existing figure on screen and every existing computation unchanged.

**Non-Goals:**

- Improving the accuracy of anything. This change describes; `add-post-play-sync` improves.
- Per-row or per-datum provenance storage. Provenance is a property of a *figure*, known statically.
- A settings toggle to hide disclosures. A figure that needs a caveat needs it for everyone.
- Removing or de-emphasizing the time-of-day pattern. It is still the best available answer to when
  the player plays; it just is not an observation.
- Reading the cloud presence poller's per-minute transition log, which would materially improve
  session boundaries. That is a real option and a separate decision — it requires an auth model the
  poller deliberately does not have yet.

## Decisions

### 1. Three terms, not two and not five

A binary exact/estimated split puts the daily playtime chart on the wrong side. Its minutes come
straight from Steam; only the *date* it lands on can be wrong, and only for play that straddles a
poll gap crossing midnight. Calling that "estimated" alongside the time-of-day pattern is the
over-hedging this change exists to avoid.

Three terms is the smallest set that separates the three real cases:

```
   observed ──────────▶ Steam said this
   tracked  ──────────▶ Steam said the amount; we chose the bucket
   inferred ──────────▶ we constructed the thing being measured
```

A fourth term for "inferred but recently corroborated" was considered and rejected. After
`add-post-play-sync` lands, a session that ended while the app was watching has a much better
boundary than one discovered cold — but the player cannot tell which is which from the figure, and a
term that varies per row is exactly the per-datum provenance this change declines to build.

### 2. At the figure, never at the screen

A screen-level banner is read once and then permanently ignored, and it is wrong in both directions
at once: over-hedging the daily chart and under-hedging the time-of-day pattern, which needs a
specific caveat no banner would carry.

The marker sits with the number. Where several figures in one card share a term — session count,
average length, and longest session are all inferred — the card declares it once, since three
identical markers in one card is noise, not disclosure.

### 3. The mechanism is explained once and linked, not repeated

The full explanation is two or three sentences: sessions are derived from a periodic poll, the start
time is the poll before the increase appeared, and a device that sleeps moves that start earlier.
Repeating that beside four figures would dominate the screen.

So: the marker is compact and consistent; selecting it opens the shared explanation. This also
means the explanation improves in one place when `add-post-play-sync` narrows the gap.

### 4. The time-of-day pattern gets a caveat of its own

Every other inferred figure degrades gracefully — a session count that is off by one is still
roughly informative. The time-of-day pattern does not: a single overnight poll gap moves a whole
evening's play into the afternoon bucket, and the figure's entire content is *which bucket*.

It therefore carries a specific statement, not just the shared term: play discovered after a long
gap is attributed to the start of that gap. A player who reads that will correctly discount an
implausible "peak time: afternoon" instead of concluding they play in the afternoon.

### 5. Provenance and confidence are orthogonal, and must combine into one sentence

Personal Pace already distinguishes **reliable** from **learning**, which is a statement about
sample size. Provenance is a statement about derivation. They compose:

```
                    │ observed        tracked          inferred
   ─────────────────┼──────────────────────────────────────────────
   reliable sample  │ —               daily chart      pace forecast
   learning sample  │ —               —                pace, first weeks
```

A pace forecast is inferred *and* possibly learning, and stacking "estimated" on "still learning"
produces a sentence nobody finishes reading. The requirement is one legible statement per figure:
where a figure is both, the confidence state is the more actionable of the two — it will change on
its own with more data — and it leads, with provenance available behind the shared explanation.

### 6. Observed is labelled too

The temptation is to mark only the doubtful figures and leave the rest bare. That makes provenance a
warning system, and warning systems get tuned out.

Marking observed figures costs almost nothing — lifetime playtime and achievement unlock times are
few and stable — and it changes the meaning of an unmarked screen: the vocabulary is describing
*where every number came from*, so the absence of a caveat is itself informative rather than an
oversight.

### 7. Announced, not merely rendered

A provenance marker delivered as a tilde, a dotted underline, or a muted tint is invisible to a
screen reader, and the figure it qualifies is the one a screen reader user is most likely to quote
back with confidence. Each figure's content description carries its term. This is stated as a
requirement rather than left to implementation, because it is the part most likely to be dropped.

## Risks / Trade-offs

- **Three terms is jargon the player did not ask for.** → Mitigated by never showing the term alone:
  each is short, plain-language, and one tap from a sentence explaining it. The alternative —
  wording each caveat bespoke per figure — produces four descriptions of one mechanism that will
  drift apart.

- **Disclosure clutters a screen that is already dense.** Analytics carries six figures, a window
  selector, and a chart. → The markers are compact and shared per card where terms coincide. If the
  result is noisy in practice, the correction is fewer markers, not vaguer ones.

- **Saying "inferred" may read as "broken".** → The wording matters and is part of the work: the
  figures are the best available answers and the app should say so. "Derived from periodic checks"
  is honest and not alarming; "may be inaccurate" is both vaguer and more frightening.

- **A caveat can become an excuse.** Labelling the time-of-day pattern as inferred is cheaper than
  improving it. → Accepted deliberately, and bounded: `add-post-play-sync` is already active and
  narrows the gap for session ends. The cloud poller's per-minute log would narrow it much further,
  and is named as an open option rather than quietly foreclosed.

- **Provenance is fixed in code, so a figure could be relabelled without being rechecked.** → The
  classification lives beside the figures it describes and is asserted in tests as a total mapping,
  so a new Analytics figure cannot ship without a term — the same forcing function the haptic
  vocabulary uses.
