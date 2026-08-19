# Haptic Feedback

## Why

The app has no haptics at all — no `VIBRATE` permission, no `performHapticFeedback`, no
`LocalHapticFeedback`. Every interaction and every earned moment is delivered through sight alone.

That is a gap in two directions. The obvious one is expressive: this is a progression app whose
whole premise is that playtime accumulates into something felt, and a level-up currently lands with
the same silence as scrolling a list. The less obvious one is accessibility — the app already takes
care that state is "perceivable without colour" and that reduced-motion degrades to a static cue.
Touch is the one redundancy channel it never uses.

The reason to do this deliberately rather than incrementally is that haptics fails in a specific,
predictable way: it gets added to whatever surface someone happens to be editing, spreads until
every tap buzzes, and is then switched off wholesale in system settings — taking the four moments
that mattered with it. Haptics is a budget. Spent evenly, it buys nothing.

There is a matching structural risk. `ui/util/ReducedMotion.kt` is the app's existing answer to a
cross-cutting sensory concern, and it states its own contract well — "the app gives one answer to
the question rather than one per feature." It is nonetheless referenced in three of thirteen UI
files, because a convention with no enforcement erodes. Adding a second such concern without
learning from that is how it gets ignored on the wayside.

## What Changes

- **A single haptic authority**, `ui/util/Haptics.kt`, as the only code in the app permitted to
  touch a platform haptic API. Every call site expresses an intent; the authority maps intent to
  effect. Retuning the app's feel is then one file, not thirteen.
- **A rationed vocabulary of eight intents**, in two tiers:
  - *Earned* — `LevelUp`, `QuestMet`, `StreakMilestone`, `StreakBroken` — driven off the
    `progress-events` stream, not off UI state.
  - *Committed* — `Confirm`, `Reject`, `Toggle` — fired where an action with consequence lands:
    a restore applied, rules saved, a snapshot deleted, the live monitor switched, selection mode
    entered.
  - Plus `Silent`, which exists so the event-to-intent mapping can decline an event explicitly.
- **Silence is the default, not an annotation.** The vocabulary is an allowlist. Navigation, list
  rows, chips, tabs, and expanders are silent because they are not in it — there is no per-site
  declaration to write or forget, and a new button that does not buzz is correct rather than
  overlooked.
- **`StreakBroken` maps to `Silent`.** The break is acknowledged by its Home overlay; a punitive
  buzz for losing something is not the register this app wants. It stays in the vocabulary so the
  decision is visible in the mapping rather than absent from it.
- **The binding rule: a haptic never fires alone.** It is the tactile channel of a moment already
  being presented visually. There is no such thing as a buzz with no visible cause, which is what
  makes the earned tier trustworthy after a background sync.
- **No `VIBRATE` permission.** Delivery is `View.performHapticFeedback`, which needs no permission
  and honours the system touch-feedback setting for free. The API-34 constants are guarded against
  the `minSdk 33` floor with a documented fallback.

## Capabilities

### New Capabilities
- `haptic-feedback`: the haptic vocabulary, the single-authority rule, the "never fires alone"
  binding to presented moments, the mapping from progress events to intents, silence-by-default,
  and the degradation rules for devices and system settings that cannot or will not deliver a given
  effect.

### Modified Capabilities
- `app-ui`: committed actions across Settings, Data & Backup, Library selection, and the live
  monitor toggle gain their haptic intent.

## Impact

- **Depends on `add-progress-events` — satisfied.** That change landed on 2026-08-13, and the
  earned tier consumes its `ProgressEvent` stream and its once-only delivery guarantee. Without it
  the earned intents would have no trustworthy trigger, since UI state cannot distinguish a
  level-up from a level that was always 5.
- **Affected code (new):** `ui/util/Haptics.kt` (the `HapticIntent` vocabulary, the dispatcher, and
  a `HapticPlayer` seam); `ui/util/ProgressEventHaptics.kt` (the exhaustive event-to-intent
  mapping); a fake player plus unit tests over the mapping.
- **Affected code (modified):** roughly 25 committed-action call sites in `ui/settings/`,
  `ui/library/`, `ui/home/`, and `ui/review/`. The remaining ~120 interactive sites are untouched
  by design.
- **A new invariant for `CLAUDE.md`**, guarding single authority rather than coverage:
  `grep -rn "performHapticFeedback\|LocalHapticFeedback\|VibrationEffect" app/src/main/java --exclude-dir=util`
  must be empty. This protects the property that actually degrades when violated — one answer for
  the whole app — which is precisely what `ReducedMotion.kt` claims and cannot enforce.
- **Deliberately no grep invariant on `clickable` / `onClick`.** Those are Material3 constructor
  parameters; banning them means wrapping every M3 component, which is building a design system.
  With silence as the default there is also nothing to catch: an unwrapped button is correct.
- **No new dependency and no Compose BOM bump.** BOM `2024.09.00` ships Compose UI 1.7, whose
  `HapticFeedbackType` has only `LongPress` and `TextHandleMove`; the richer platform palette is
  reached through `LocalView` instead, and the dispatcher absorbs that choice so no call site knows.
- **No permission change, no manifest change, no migration, no network, no cloud.**
