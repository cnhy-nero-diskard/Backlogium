## Context

`CollectionScreen`'s overview already assembles everything a picker needs. `CollectionMemberUi`
carries the member's identity, playtime, session count, and trophy counts; `CollectionSummary`
derives the aggregate; `CollectionMode` distinguishes the four intents a collection can carry. The
overview is where the player is already looking at exactly the shortlist in question.

What the app has no notion of at all is *arbitrary*. Every other surface that names a game names it
for a reason — highest playtime, next in sequence, most at risk against a deadline, closest to done.
The four modes are four opinions:

```
   BASIC            "these belong together"          → no answer to "which"
   COMPLETION_GOAL  "finish all of these"            → no answer to "which first"
   DEADLINE_GOAL    "all of these by a date"         → pace, not order
   ORDERED_QUEUE    "in this order"                  → the player already decided
```

Three of the four leave "which one tonight" open, and the fourth answers it only because the player
sat down and answered it themselves.

## Goals / Non-Goals

**Goals:**

- Produce an arbitrary answer the player can accept precisely because it is arbitrary.
- Make the candidate set visible, so the answer is trustworthy.
- End on a concrete next step rather than an animation.
- Establish a result surface with a slot a later launch verb can occupy.
- Add nothing to storage and nothing to the network.

**Non-Goals:**

- Being a good recommender. Being a *fair* one.
- Any memory of past picks.
- Whole-library selection.
- Coupling to the desktop agent, in any direction, including a disabled placeholder for it.

## Decisions

### 1. Uniform, and said out loud

A weighted wheel is indistinguishable from an unweighted one at the moment of use, and the player
cannot audit it. If the app decides shortest-first is better, that is a recommendation and belongs
somewhere that says so — `add-smart-collections`' Quick wins is literally that list.

The wheel's entire value proposition is that it did not think about it. So: uniform over eligible
members, stated on the surface, with the eligible count shown. That statement is what makes the
result usable — "it picked this out of seven" is a fact; "it picked this" is a suggestion.

### 2. Eligibility comes from the collection, never from the app's judgment

Two candidate exclusions were considered:

| Exclusion | Verdict |
|---|---|
| ordered-queue member marked done | **excluded** — the player explicitly said so about this collection |
| all achievements unlocked | **kept in** — a fact about achievements, not about wanting to replay |

The second is the interesting one. It looks obviously right and is not: the app's only notion of
"completed" is achievement completion, which says nothing about whether a game is worth an evening.
A comfort game replayed yearly is 100% and is a perfectly good answer. Excluding it means the app
quietly overrode a shortlist the player curated by hand — which is exactly the judgment this feature
exists to avoid making.

Where the pool is smaller than the collection, the surface says so and says why. An unexplained
exclusion is worse than no exclusion.

### 3. Re-spin excludes the previous result

Without this rule a two-member collection lands on the same game twice in a row roughly half the
time, and reads as broken. With it, re-spinning always moves:

```
   pool > 1 candidate besides the last result   →  uniform over the rest
   pool == 1 candidate besides the last result  →  that one (a 2-member collection alternates)
   pool == 1 member total                       →  no roulette offered at all
```

The alternation in a two-member collection is deterministic, which is technically not random. That
is the correct trade: with two members the player is asking for a coin flip they can re-flip, and
"the other one" is what re-flipping means to them.

The exclusion is in-memory for the visit. Persisting it would make the die remember, and a die that
remembers has an opinion.

### 4. The result is a decision surface, and the slot is the point

```
   ┌─────────────────────────────────────────┐
   │  ▚▚  Hollow Knight                      │
   │      chosen from 7 of 9 members         │
   │                                         │
   │  [ Open ]  [ Spin again ]               │
   │  [ ── action slot ─────────────────  ]  │  ← queue-front today
   │                                         │     "Launch on desktop" later
   └─────────────────────────────────────────┘
```

Today the slot holds exactly one thing, and only for `ORDERED_QUEUE`: move the picked game to the
front of the sequence. That is a real commit against real stored state, and it is the natural
follow-through when a queue's owner decides to jump the line.

For the other three modes the slot is empty today. It is specified as a slot rather than as a button
because `add-remote-launch` will put "start it on the desktop" there, and the difference between a
surface designed to hold that and one retrofitted for it is the difference this change is trying to
establish. No placeholder is rendered for an action that does not exist.

### 5. Motion is decoration; the result is not

The app already has a rule for this — the now-playing card must remain legible with motion disabled
— and a spinning wheel is a much stronger claim on attention than an ambient pulse. So:

- Under a reduced-motion preference, the result appears directly. No shortened spin, no fade
  substitute: the animation's purpose is theatre and theatre is what the preference declines.
- The result carries no meaning that only the animation conveys.
- The wheel does not use the milestone accent. Gold is reserved for level-ups, streak milestones, and
  completion, and a dice roll is none of those. The collection's own stored accent is the right
  colour: it is already the visual identity of the shortlist being drawn from.

### 6. Spinning is silent, and that is a decision

The haptic vocabulary is closed and rationed, and "silence is the default" makes an unadorned
interaction correct rather than incomplete. Spinning commits nothing and reveals nothing earned, so
it names no intent. Moving a game to the front of a queue does commit, and uses the existing success
intent.

The temptation is a tick-tick-tick under the spin. It is exactly the kind of decorative haptic the
vocabulary was written to prevent, and adding an intent for it would mean every future surface can
argue for one too.

### 7. Randomness is injected

The selector takes a source of randomness as a parameter. Testing uniformity statistically is slow
and flaky; testing "given this source, this pick" and "given the previous result, never this one" is
neither. This also keeps the selector pure, matching every other derivation in `domain/`.

## Risks / Trade-offs

- **A dice roll can read as filler.** → Mitigated by the action slot: the feature is only interesting
  because it ends in a step. If the slot stays empty for three of four modes indefinitely, the
  feature is thin — which is an argument for sequencing `add-remote-launch` behind it, not for
  weakening the surface now.

- **Keeping 100%-completed games in the pool will annoy someone.** → Accepted, with the mitigation
  that the pool size and rule are visible: a player who does not want them can remove them from the
  collection, which is the honest fix. Excluding them silently would be the app editing a hand-made
  shortlist.

- **Re-spinning defeats the purpose.** Someone will spin until they get the answer they wanted. → That
  is fine, and arguably the feature working: discovering you were hoping for a particular game *is*
  making the decision. The no-repeat rule is there so this is not mistaken for a malfunction.

- **The wheel is the most decorative thing in the app.** The app's visual identity is restrained and
  deliberately so. → Constrained by the existing rules — no milestone accent, the collection's own
  accent, reduced-motion honoured, legible without motion. If it still feels out of place, the fix is
  a quieter reveal, not a new colour.

- **Two members makes it deterministic.** → Named rather than papered over. The alternative — allowing
  an immediate repeat — is worse in the only case where it is observable.
