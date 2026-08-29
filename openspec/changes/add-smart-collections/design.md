## Context

`Collection` is a Room table carrying `displayOrder`, `accent`, `mode`, `targetDate`, and
`description` — app-owned state the sync worker never reads or writes. Membership is explicit rows
in `CollectionMember`.

Derived lists are the opposite in every respect that matters:

```
   custom collection          derived collection
   ─────────────────          ──────────────────
   membership chosen          membership implied
   changes when edited        changes when the calendar advances
   ordered by the user        ordered by the rule
   carries an intent          carries an observation
```

The inputs all exist. `Game` holds `playtimeForever` and `backfillMinutes`; `Session` holds
per-session `minutes` with an `appId` index; `HltbData` holds `mainStoryMinutes`;
`achievementRepository.counts` holds unlocked and total per game. `HomeViewModel` already combines
the first, third, and fourth into `CollectionMemberSignals`.

There is no app-wide notion of a completed game. `CollectionSummary` has a `completionFraction` and
an `isComplete` at `>= 1.0`, but both are scoped to a collection's chosen `timeBasis` and exist to
drive banners.

## Goals / Non-Goals

**Goals:**

- Surface the patterns the library already contains, without the player having to sort for them.
- Make every membership explicable — the rule is visible and the reason a game qualifies is stated.
- Stay correct across a day boundary with no sync and no user action.
- Add no table, no migration, and no recompute job.
- Leave custom collections entirely unchanged.

**Non-Goals:**

- User-defined rules or a query builder. Five fixed lists, not a filtering language.
- Tunable thresholds in this change.
- Any Home surface the player can arrange: the derived group is fixed, last, and read-only.
- Modes, target dates, ordering, accents, or descriptions on derived lists.
- Editing membership by any means, including a one-off exclusion.
- Promoting a derived list into a custom collection. Worth doing, and worth its own decision.

## Decisions

### 1. Derived on read, as a pure function

`SmartCollections` takes library games, achievement counts, and `today`, and returns each list's
membership. No Room types, no Android, no injection — the same shape as `CollectionSummary.derive`,
`SessionDiffer`, `Gamification`, and `RarityStanding`. `SmartCollectionFeed` is the thin injected
wrapper that assembles its inputs from repositories once, for every surface that presents the
lists.

This is not only idiom. Membership is a function of the calendar: a game becomes dropped because
thirty days elapsed, not because anything was observed. Materialized membership would need a daily
job to stay true, a staleness window while it hadn't run, and rows that could disagree with the
facts they were derived from. Deriving on read makes the day boundary correct with no machinery at
all.

Cost is negligible: one pass over a few hundred games against data the screen already observes.

### 2. No session-length threshold

Every recorded session counts as play. There is no fifteen-minute floor.

The floor existed to protect one case: a game relaunched briefly after months should still read as
dropped. It bought that at a price that turned out to be far higher than the case was worth —
because the same threshold defined which games had *any* usable history, it silently excluded every
game the app had never watched closely, which is most of a library on the day it is installed.

Dropped now takes its date from Steam's last-played stamp (Decision 4), so the relaunch case is
handled by the fact rather than by a filter: a game launched today was, in fact, played today, and
saying otherwise was always a small fiction. What remains is simpler in every direction — no
threshold constant, no filtered aggregate, and no second meaning for the word "session" that only
this feature used.

The cost is honest and small: a two-minute launch to change a setting does start a game and does
resume a dropped one. That is the same thing Steam's own library says, and disagreeing with Steam
about whether a game was launched is not a position this app should hold.

### 3. Completion is achievements-first, playtime-fallback, and never guessed

```
   has achievements?  ──yes──▶  all unlocked?  ──▶  completed
                                                    (rule: achievements)
        │
        no
        ▼
   has main story length?  ──yes──▶  playtime ≥ it  ──▶  completed
                                                         (rule: playtime)
        │
        no
        ▼
   not classifiable — excluded from the list entirely
```

Achievements come first because they are evidence of what the player *did*; playtime is evidence of
how long they were present. A 200-hour roguelike passes every playtime threshold and was never
completed, while a story game finished under the HLTB average fails one.

The fallback exists because a large share of games have no achievements at all, and excluding them
outright would make the list quietly wrong rather than usefully incomplete.

**Almost done carries the achievement condition too.** Completion asks whether every achievement is
unlocked; almost-done asks whether eighty percent are, alongside the playtime test. The list is
worthless without it — a forty-hour roguelike sails past any playtime threshold with a third of its
achievements locked, and calling that "almost done" discredits every other row in the list. Where a
game genuinely has no achievements, playtime decides alone; where they have simply never been
fetched, the game is absent, on the same principle that governs a missing HowLongToBeat length.

**Each member states which rule placed it there.** A list that silently mixes two definitions
invites exactly one question — why is that in here — and a list that cannot answer it is worse
than no list. Main story is the fallback basis, not completionist: the question is whether the
player finished the game, not whether they exhausted it.

Games with neither signal are excluded rather than assumed incomplete. Absence of evidence is not
evidence, and the same principle already governs trophy counts in `custom-collections`, where
missing data must stay distinguishable from zero.

### 4. Dropped takes its date from Steam, with observed sessions as the more recent authority

The rule needs a *date* — when the game was last played. The original design looked only at
`Session`, on the reasoning that `playtimeForever` and `backfillMinutes` are totals with no time
attached, and concluded that a game the app never watched could not be judged abandoned.

That reasoning missed a column. `Game.lastPlayedAt` already holds Steam's own `rtime_last_played`,
written by the sync worker on every pass. It is exactly the date the rule wanted, it covers the
entire library including the years before this app existed, and requiring an observed session in
preference to it excluded precisely the games most likely to have been abandoned.

So last play is the later of Steam's stamp and the most recent observed session. Steam supplies the
history; a session supplies what Steam does not report — a family-shared game, or a play more recent
than the last sync.

The consequence is deliberate and was the point of asking: a freshly configured library can show a
substantial Dropped list on day one. Those games *were* abandoned; the earlier design's empty list
was not a more honest answer, only a quieter one. A game whose last play no source knows is still
excluded, because nothing establishes that it was abandoned rather than never touched.

The dropped floor drops to an hour and a half at the same time. Two hours excluded a category of
game that is genuinely abandoned — a short indie given one evening and never reopened.

### 5. Lists overlap, deliberately

A game at 85% of its main story, untouched for two months, belongs in both **Almost done** and
**Dropped**. Forcing exclusivity would require a precedence order that discards the more
interesting half of what the app knows.

That combination is arguably the single most actionable thing in the library — nearly finished and
quietly abandoned — so the design surfaces both rather than picking one.

The two exclusions that do apply are semantic rather than structural: **Dropped** and **Almost
done** both exclude completed games, because a finished game is neither abandoned nor nearly done.

### 6. Home shows them, last and unmovable, below a dashed rule

Home's collection banners exist to surface intent the player chose: a deadline they set, a queue
they ordered. A derived list carries no intent — it is an observation the app made. That difference
is real, and it is what the layout has to express; it is not, on reflection, a reason to withhold
the observation from the screen the player actually opens.

```
   Collections                    ← chosen: ordered, accented, draggable
     [ Finish before December ]
     [ Weekend queue          ]
   - - - - - - - - - - - - - -    ← the boundary, drawn
   Derived collections            ← observed: fixed order, no gestures
     [ Almost done          3 ]
     [ Dropped             11 ]
```

The dashed rule does the whole job. Above it, everything responds to a long-press and remembers
where the player put it; below it, nothing does. A read-only list mixed in among draggable ones
would invite a gesture that silently fails, which is worse than not showing it at all — so the
separation is what makes the inclusion safe rather than a decoration on it.

The tone argument that originally kept them off Home stands, and per-list visibility answers it:
anyone who does not want a standing count of abandoned games hides that one list, once, and it is
gone from both surfaces.

### 7. Visibility is per-list, at the point of use

Each list can be hidden individually, controlled from the Collections screen rather than Settings.
Display preferences in this app already live where their effect is visible — `GameListDensityControl`
sits in the list it governs — and a toggle three screens away from the thing it hides is worse than
one beside it.

An empty list is hidden regardless of its toggle. A fresh library would otherwise show five rows
saying nothing, which teaches the player the feature is noise before it has had a chance to be
useful.

### 8. Fixed thresholds, stated visibly

```
   dropped: minimum playtime    1.5 hours
   dropped: idle period          30 days
   quick win: main story max      6 hours
   almost done: main story        80%
   almost done: achievements      80%
```

`app-settings` already carries editable gamification rules with a retroactive-effect disclosure, so
making these tunable would follow an established pattern rather than invent one. It is deliberately
not done here: five lists' worth of knobs is a large configuration surface to add before any of it
has been lived with, and the rules screen earns its complexity because those numbers drive XP.

The compensating requirement is that every threshold is **visible on the list it governs**. A
visible fixed rule is more useful than a hidden adjustable one, and the visible version is what
tells you which number actually chafes.

## Risks / Trade-offs

- **"Dropped" can be long on the first day**, because Steam's last-played history reaches back
  years before the app was installed. → Accepted, and asked for. Those games really were abandoned;
  a list that stayed empty until the app had watched them itself was withholding an answer it
  already had. The list can be hidden by anyone who does not want it.

- **"Quick wins" and "Almost done" depend on HowLongToBeat coverage.** Games without a match simply
  cannot appear. → Consistent with how the app already treats missing HLTB data, and the existing
  match-review surfaces are the remedy. Worth noting on the lists themselves so an absence reads as
  missing data rather than an empty library.

- **A momentary launch now counts as play.** Opening a game to change a setting starts it and
  un-drops it. → The honest reading of the fact, and the same one Steam's own library shows. The
  previous threshold's cost — excluding every game the app never watched from Dropped — was far
  larger than this.

- **"Almost done" is empty until achievements have been fetched.** A library synced without
  achievement data shows nothing there. → Correct rather than unfortunate: without achievements the
  rule cannot be evaluated, and the alternative is a list built on the half of the condition that
  produces false members. The screen says so, so an absence reads as missing data.

- **Deriving on every read repeats work across screens.** → A single pass over a few hundred games
  against already-observed flows. If it ever registers, the fix is caching the derivation per
  emission, not materializing membership.

- **Five lists is an opinion about what matters.** → Deliberately so, and deliberately small. A
  query builder would be more general and much less useful; nobody opens a backlog app wanting to
  write a predicate.

- **"Dropped" is a judgment the app makes about the player.** → Named as chosen rather than
  defaulted. The rule is stated so the label is a description rather than a verdict, and the list
  can be hidden outright by anyone who does not want to be told.
