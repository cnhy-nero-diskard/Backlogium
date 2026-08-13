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
- Any Home surface.
- Modes, target dates, ordering, accents, or descriptions on derived lists.
- Editing membership by any means, including a one-off exclusion.
- Promoting a derived list into a custom collection. Worth doing, and worth its own decision.

## Decisions

### 1. Derived on read, as a pure function

`SmartCollections` takes library games, per-game session summaries, achievement counts, and
`today`, and returns each list's membership. No Room types, no Android, no injection — the same
shape as `CollectionSummary.derive`, `SessionDiffer`, `Gamification`, and `RarityStanding`.

This is not only idiom. Membership is a function of the calendar: a game becomes dropped because
thirty days elapsed, not because anything was observed. Materialized membership would need a daily
job to stay true, a staleness window while it hadn't run, and rows that could disagree with the
facts they were derived from. Deriving on read makes the day boundary correct with no machinery at
all.

Cost is negligible: one pass over a few hundred games against data the screen already observes.

### 2. The meaningful-session threshold, at 15 minutes

A session under fifteen minutes does not count as playing.

This came out of a specific observation — a game relaunched briefly after months should still read
as dropped — but it generalises further than the case that produced it. Without it, "never started"
is defeated by a three-minute install check, and "dropped" resets every time a game is opened to
adjust a setting.

Fifteen minutes is the number the requirement was described with, and it is a reasonable floor for
"I actually played this." It is fixed rather than derived, and stated on the lists that depend on
it.

`Session.minutes` supports this directly — the threshold applies per session, not to a total.

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

**Each member states which rule placed it there.** A list that silently mixes two definitions
invites exactly one question — why is that in here — and a list that cannot answer it is worse
than no list. Main story is the fallback basis, not completionist: the question is whether the
player finished the game, not whether they exhausted it.

Games with neither signal are excluded rather than assumed incomplete. Absence of evidence is not
evidence, and the same principle already governs trophy counts in `custom-collections`, where
missing data must stay distinguishable from zero.

### 4. Dropped requires session history, not just playtime

The rule needs a *date* — when the game was last meaningfully played — and only `Session` carries
one. `playtimeForever` and `backfillMinutes` are totals with no time attached.

This matters immediately on a fresh install. A player who imports Steam history has hundreds of
games with substantial playtime and zero sessions. Without a guard, "Dropped" would open containing
most of their library on day one, which is both wrong and the worst possible first impression of
the feature.

So a game qualifies as dropped only if it has at least one meaningful session on record. The app
cannot know you abandoned something it never watched, and saying so by omission is more honest than
inferring a date from a total. The list fills in as history accumulates.

### 5. Lists overlap, deliberately

A game at 85% of its main story, untouched for two months, belongs in both **Almost done** and
**Dropped**. Forcing exclusivity would require a precedence order that discards the more
interesting half of what the app knows.

That combination is arguably the single most actionable thing in the library — nearly finished and
quietly abandoned — so the design surfaces both rather than picking one.

The two exclusions that do apply are semantic rather than structural: **Dropped** and **Almost
done** both exclude completed games, because a finished game is neither abandoned nor nearly done.

### 6. Collections screen only, never Home

Home's collection banners exist to surface intent the player chose: a deadline they set, a queue
they ordered, a completion goal they declared. A derived list carries no intent — it is an
observation the app made.

There is also a tone argument. Home is the first thing seen on opening the app, and `app-ui` guards
its attention deliberately. A permanent count of abandoned games there is a standing reproach, not
a feature. In the Collections screen the same list is something the player went looking for.

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
   meaningful session            15 minutes
   dropped: minimum playtime      2 hours
   dropped: idle period          30 days
   quick win: main story max      6 hours
   almost done: main story        80%
```

`app-settings` already carries editable gamification rules with a retroactive-effect disclosure, so
making these tunable would follow an established pattern rather than invent one. It is deliberately
not done here: five lists' worth of knobs is a large configuration surface to add before any of it
has been lived with, and the rules screen earns its complexity because those numbers drive XP.

The compensating requirement is that every threshold is **visible on the list it governs**. A
visible fixed rule is more useful than a hidden adjustable one, and the visible version is what
tells you which number actually chafes.

## Risks / Trade-offs

- **"Dropped" starts empty and fills slowly**, because it needs observed session history rather
  than imported totals. → Accepted as the honest behaviour. The alternative declares most of a
  freshly-imported library abandoned on first launch, which would be wrong in a way the player can
  immediately see.

- **"Quick wins" and "Almost done" depend on HowLongToBeat coverage.** Games without a match simply
  cannot appear. → Consistent with how the app already treats missing HLTB data, and the existing
  match-review surfaces are the remedy. Worth noting on the lists themselves so an absence reads as
  missing data rather than an empty library.

- **Fifteen minutes will be wrong for someone.** A daily ten-minute puzzle habit registers as never
  playing. → The cost of a lower threshold is worse: install checks and setting tweaks would count
  as sessions, which breaks the case the threshold exists to handle. Fixed and visible, revisited
  if it bites.

- **Deriving on every read repeats work across screens.** → A single pass over a few hundred games
  against already-observed flows. If it ever registers, the fix is caching the derivation per
  emission, not materializing membership.

- **Five lists is an opinion about what matters.** → Deliberately so, and deliberately small. A
  query builder would be more general and much less useful; nobody opens a backlog app wanting to
  write a predicate.

- **"Dropped" is a judgment the app makes about the player.** → Named as chosen rather than
  defaulted. The rule is stated so the label is a description rather than a verdict, and the list
  can be hidden outright by anyone who does not want to be told.
