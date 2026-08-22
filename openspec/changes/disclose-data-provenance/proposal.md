# Disclose Data Provenance

## Why

The app presents a number it measured and a number it guessed in the same typeface.

Steam exposes no session data. Every "session" in Backlogium is synthesized by diffing
`playtime_forever` between polls, and `SessionDiffer` sets a new session's start to **the previous
poll's timestamp** — the only estimate available. That is a sound design; it is also the reason
several figures mean less than they look like they mean:

```
   real world   19:00 ─────── played 40 minutes ─────── 19:40      device dozing
   polls     ×───────────────────────────────────────────────────×
          13:00                                                20:15
   stored    Session(startAt = 13:00, minutes = 40)
```

Nothing here is a bug. The minutes are exactly right — they are Steam's own counter. What is wrong
is the *time*, and the Analytics screen spends four of its six figures on times:

| Figure | What it actually is |
|---|---|
| Time-of-day pattern | a histogram of **when the app happened to poll**, in the worst case |
| Session count | runs of consecutive polls showing an increase — not launches |
| Average session length | minutes per such run |
| Longest session | the longest such run |
| Daily playtime chart | Steam's minutes, on a day boundary that can be misattributed |
| Most-played in window | the same, aggregated — the most robust figure on the screen |

The History screen already gets this right for exactly one figure: a session's start renders as
`~12:43 AM`, and the spec requires it be "presented as approximate, reflecting that session
boundaries are derived from periodic polling rather than observed directly". That reasoning was
never generalized. Analytics carries no such statement anywhere, and it is the screen where a
player goes specifically to draw conclusions.

The remedy is not a banner. A screen-level "this data may be inaccurate" casts identical doubt on
the daily-minutes chart, which is very nearly exact, and on the time-of-day pattern, which can be
nonsense — and a hedge that applies to everything is read as applying to nothing.

## What Changes

- **A three-term provenance vocabulary**, defined once and used everywhere a figure is presented:

  | Term | Meaning | Examples |
  |---|---|---|
  | **Observed** | Steam's own value, stored as reported | lifetime playtime, achievement unlock time, global unlock percentage, current player count |
  | **Tracked** | the *amount* is Steam's; the *window it lands in* is the app's | daily playtime, most-played within a window, per-day quest totals |
  | **Inferred** | the *boundary itself* is the app's estimate | session start, session count, session length, time-of-day bucket |

- **Provenance is declared at the figure, not at the screen.** A figure states its term where it is
  read, so the daily chart and the time-of-day pattern are not tarred with the same brush.
- **The mechanism is explained once and reached from any inferred figure**, rather than repeating a
  paragraph beside each. The explanation names the poll cadence and the specific consequence: play
  is attributed to the interval in which it was *discovered*, so a long gap between polls moves a
  session earlier than it happened.
- **The time-of-day pattern carries the strongest disclosure of any figure**, because it is the one
  whose entire meaning rests on inferred timestamps, and states that a device that sleeps through a
  play session shifts that session's bucket.
- **History's existing approximate-start treatment is restated as an instance of the vocabulary**
  rather than a local one-off, so the two screens describe the same limitation the same way.
- **Provenance is a property of the figure, not a per-datum flag.** Nothing is stored, no schema
  changes, and no computation changes. Every number the app shows today, it still shows.
- **Provenance and confidence stay distinct.** Personal Pace already distinguishes a *reliable*
  forecast from a *learning* one — that is a statement about sample size. Provenance is a statement
  about derivation. A surface may need both, and the change requires they combine into one legible
  sentence rather than two stacked hedges.

## Capabilities

### New Capabilities
- `derived-data-provenance`: the vocabulary's three terms and their definitions, the rule that a
  figure declares its own term where it is read, the requirement that the mechanism be explained
  once and reachable rather than repeated, the classification of each existing figure, and the
  boundary between provenance and statistical confidence.

### Modified Capabilities
- `app-ui`: the Analytics screen declares provenance per figure and gives the time-of-day pattern
  its specific disclosure; the History screen's approximate-start rule is expressed in the shared
  vocabulary; Home's now-playing elapsed time and the collection Personal Pace presentation adopt
  the same terms.

## Impact

- **Presentation only.** No entity, no DAO, no migration, no request. `SessionDiffer`,
  `GamificationUpdater`, `AnalyticsWindow`, and `groupHistory` are untouched, and every stored value
  keeps the meaning it has today.
- **Affected code (new):** a small provenance vocabulary in `ui/util/`, beside `UiFormat` — which
  already owns `approxTime`, the one existing expression of this idea — plus the shared explanation
  surface.
- **Affected code (modified):** `ui/analytics/AnalyticsScreen.kt` (per-figure labels and the
  time-of-day disclosure), `ui/history/HistoryScreen.kt` (the existing `~` treatment routed through
  the vocabulary), `ui/home/` (elapsed time), `ui/collections/CollectionPacingPresentation.kt`
  (combining provenance with the existing learning state).
- **Accessibility is part of the requirement, not a follow-up.** A provenance marker rendered only
  as a glyph or a muted colour is not a disclosure. Each figure's announced description carries its
  term.
- **This raises the floor under later work.** `extend-history-before-tracking` introduces days whose
  play is evidenced but whose minutes are unknown, and `add-post-play-sync` narrows the inference
  gap for sessions that end while the app can observe them. Both need somewhere to say what a
  figure is made of, and this change is that place.
- **Nothing is hedged that does not need hedging.** Lifetime playtime, achievement unlock times, and
  global unlock percentages are Steam's own and are labelled as such — the vocabulary's job is as
  much to identify what *is* solid as to flag what is not.
