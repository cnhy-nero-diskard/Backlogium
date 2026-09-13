# Collection Roulette

## Why

A collection is a shortlist the player made deliberately, and then had to choose from anyway.

The app already answers two of the three questions a shortlist raises. `ORDERED_QUEUE` answers
"what's next" — deterministically, from a sequence the player wrote. `DEADLINE_GOAL` answers "am I
going to make it". Neither answers the one that actually stalls an evening: **I curated these
because I want to play all of them, and I have no preference tonight.**

That is not a ranking problem, and treating it as one is why it stays unsolved. Any recommender —
shortest first, least played, closest to done — is an opinion the player would have to agree with,
and `add-smart-collections` is already where the app forms opinions about which games deserve
attention. This is the opposite request: *stop making me decide*. The correct answer is arbitrary,
and the value is entirely in it being external.

The reason it is worth building now rather than as a novelty is the shape it establishes. A picker
that names a game and stops is a toy. A picker that names a game **and offers the next concrete
step** is a decision surface — and the step it cannot yet offer is the one this app is otherwise
uniquely positioned for: *start it on the desktop in the other room*. `add-desktop-agent` and
`add-remote-launch` supply that verb later. Building the surface that will hold it, with a defined
slot for a committing action, is the small precedent worth setting first.

## What Changes

- **A roulette on the collection overview**, offered when a collection has at least two eligible
  members. It picks one, uniformly at random, from the collection's own members.
- **Uniform, and stated as uniform.** No weighting by length, playtime, completion, or recency. A
  weighted wheel is a recommender wearing a costume, and the player cannot tell which it is.
- **Eligibility is the collection's own judgment, never the app's.** The pool is every member except
  those the collection itself has marked done — an ordered-queue member struck through. A game whose
  achievements are all unlocked stays in: "100% completed" is a fact about achievements, not a
  statement that the player is finished with it, and the app has no business ruling it out.
- **The pool is stated, not implied.** The surface says how many members it is choosing from and, when
  that is fewer than the collection holds, why the others were left out. A picker whose candidate set
  is invisible cannot be trusted to be fair.
- **Re-spinning is allowed and does not repeat.** The immediately previous result is excluded from
  the next spin whenever another candidate exists, so a two-member collection alternates rather than
  landing on the same game twice and appearing to be broken.
- **Nothing is recorded.** No pick history, no "you rolled this three times", no persisted state. The
  previous-result exclusion lives only for the current visit. A die that remembers is not a die.
- **The result is a decision surface with an action slot.** It names the game and offers to open its
  detail, plus one mode-aware committing action where the collection has a sequence to change —
  moving the picked game to the front of an ordered queue. The slot is where a later change attaches
  "launch it on the desktop"; this change adds no agent coupling of any kind.
- **The spin is decoration and is treated as such.** Under a reduced-motion preference the result is
  revealed directly. The result is fully legible with no animation at all, and the wheel does not use
  the accent reserved for milestone moments.

## Capabilities

### New Capabilities
- `collection-roulette`: what the candidate pool is and how exclusions are disclosed, the uniform
  selection rule, the no-immediate-repeat rule on re-spin, the statelessness of the feature, what
  the result offers and what it commits, and the conditions under which the roulette is not offered
  at all.

### Modified Capabilities
- `app-ui`: the collection overview offers the roulette and presents its result, with motion and
  haptic treatment consistent with the app's existing rules.

## Impact

- **No storage, no schema, no migration, no network, no permission.** Selection runs over the member
  list the collection overview already has in hand.
- **Affected code (new):** a pure selector in `domain/` taking the eligible members and the previous
  result and returning a pick, in the shape `CollectionSummary.derive` and `SmartCollections` already
  use; the result presentation and its spin.
- **Affected code (modified):** `ui/collections/CollectionScreen.kt` (the overview entry point and
  result surface), `ui/collections/CollectionViewModel.kt` (eligibility and the in-memory previous
  result).
- **No haptic vocabulary change.** Moving a game to the front of a queue is an action with a
  consequence and uses the existing success intent. Spinning and revealing commit nothing, so under
  the existing "silence is the default" rule they are silent — a deliberate answer, not an omission.
- **Randomness needs a seam for tests.** The selector takes its randomness as a parameter rather than
  reaching for a global source, so its fairness and its no-repeat rule are testable without
  statistics.
- **Deliberately not on Home.** Home's collection cards are teasers into a collection; the decision
  belongs where the members are.

## Non-goals

- Weighting, filtering, or excluding by any property the app judges — length, completion, playtime,
  or recency. Those are `add-smart-collections`' subject.
- A roulette over the whole library. A shortlist the player curated is what makes an arbitrary answer
  acceptable; over 900 owned games it would be noise.
- Recording picks, or any notion of a pick being honoured or ignored.
- Launching anything. The action slot exists; the verb arrives with `add-remote-launch`.
- Marking the picked game as a goal, adding it to the tracked set, or changing any collection
  property other than an ordered queue's sequence when the player explicitly asks for that.
