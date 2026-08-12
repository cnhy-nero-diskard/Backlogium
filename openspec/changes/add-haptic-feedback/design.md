## Context

The app ships no haptics today. The relevant constraints are fixed and worth stating before any
decision rests on them:

- `minSdk 33`, `targetSdk 36`, `compileSdk 36`.
- Compose BOM `2024.09.00` → Compose UI 1.7. `HapticFeedbackType` there has exactly two members,
  `LongPress` and `TextHandleMove`. The expanded set (`Confirm`, `Reject`, `ToggleOn/Off`,
  `SegmentTick`, `ContextClick`) arrives in Compose UI 1.9.
- `HapticFeedbackConstants.CONFIRM` / `REJECT` / `GESTURE_START` / `GESTURE_END` are API 30.
  `TOGGLE_ON` / `TOGGLE_OFF` / `SEGMENT_TICK` / `DRAG_START` are API 34 — above the floor.
- `View.performHapticFeedback` requires no permission and respects the system touch-feedback
  setting. `Vibrator` / `VibrationEffect` requires `android.permission.VIBRATE` and leaves honouring
  the user's preference to the caller.
- `ui/util/ReducedMotion.kt` is the existing precedent for a cross-cutting sensory concern, and is
  referenced by 3 of 13 UI files despite claiming to be the app's single answer.
- 146 click, long-click, and gesture sites exist across 13 UI files.

## Goals / Non-Goals

**Goals:**

- One place in the app touches a platform haptic API, and that property is mechanically checkable.
- The earned tier is triggered by transitions, never by state, so a background sync cannot produce
  a buzz that contradicts what is on screen.
- The vocabulary is small enough that a player never habituates to it.
- The mapping is unit-testable without a device.
- Adding a progress event later cannot silently skip the haptic decision.

**Non-Goals:**

- Custom vibration waveforms. No `VIBRATE` permission is taken in this change.
- An in-app haptics on/off setting. The system setting is the control surface.
- Touch feedback on navigation, scrolling, list rows, chips, tabs, or expanders.
- A design-system wrapper layer over Material3 components.
- Any change to what the progress-event stream produces. This change consumes it.

## Decisions

### 1. Silence is the default; the vocabulary is an allowlist

The initial framing of this work was a per-site audit — visit all 146 interactive sites and declare
each one's intent, with an explicit `Silent` where the answer is no. That is the wrong shape. It
produces roughly 120 annotations whose entire content is "nothing happens," each of which is a
place to be inconsistent, and it makes every future button a site of obligation.

Inverting it costs nothing and removes the whole class of problem: **the spec states that surfaces
outside the vocabulary are silent.** A new button that does not buzz is correct by construction.
There is no coverage to enforce, because there is no coverage requirement.

`Silent` survives, but only in one place — the `ProgressEvent` → `HapticIntent` mapping, where an
exhaustive `when` demands a value for every event and `StreakBroken`'s answer is "none." That is
the one context where declining needs to be written down, because it is a decision about an event
the app *does* consider significant.

*Alternative considered:* `Modifier.hapticClickable(intent)` replacing raw `clickable` everywhere,
enforced by a grep invariant. Rejected — `onClick` is a Material3 constructor parameter on
`Button`, `IconButton`, `NavigationBarItem`, and `FilterChip`, so the rule is unenforceable without
wrapping every component, and the existing `CLAUDE.md` invariants guard architectural breaches
rather than polish gaps. A grep that needs a growing `--exclude-dir` list stops meaning anything.

### 2. Enforce single authority, not coverage

The property that actually degrades when this erodes is not "some button lost its buzz" — it is
"two parts of the app disagree about how a confirmation feels." That is checkable:

```bash
grep -rn "performHapticFeedback\|LocalHapticFeedback\|VibrationEffect" \
  app/src/main/java --exclude-dir=util
```

Empty, always. One `--exclude-dir`, for the file that *is* the authority, matching the shape of the
repository-boundary invariant already documented in `CLAUDE.md`.

Combined with the exhaustive `when` in the event mapping, that gives two enforcement points — one
grep, one compile error — neither of which depends on anyone remembering a convention.

### 3. A haptic never fires alone

Stated as a rule in the spec, in the register `ReducedMotion.kt` uses for its own contract:

> A haptic is the tactile channel of a moment that is already being presented. It never fires alone.

This resolves the hardest problem in the earned tier without any additional machinery. Progress
events can be produced by a sync while the app is closed; delivering the haptic when the event is
*presented* rather than when it is *produced* means the buzz always coincides with something the
player can see and attribute. There is no mystery vibration, and no need for a staleness horizon
that would otherwise have to discard real moments.

It also collapses "should this buzz?" into a question the design already answers: is something
being celebrated?

### 4. `View.performHapticFeedback`, not `Vibrator`

`performHapticFeedback` needs no permission, respects the system touch-feedback setting without the
app implementing that check, and scales to the device's actuator. `VibrationEffect.startComposition()`
with `PRIMITIVE_QUICK_RISE` / `PRIMITIVE_SPIN` would make a level-up genuinely distinctive, at the
cost of the `VIBRATE` permission, OEM-dependent behaviour, and taking on responsibility for
honouring a preference the platform already honours.

The honest trade: on platform constants alone, `LevelUp` and `Confirm` will feel *similar*. There
are four earned moments in the whole app, so that is a permission bought for four moments. Ship on
constants; the dispatcher is the single place that maps intent to effect, so revisiting this later
is a one-file change rather than a refactor. Recorded here so the next reader knows it was weighed,
not overlooked.

*Not bumping the Compose BOM* for `HapticFeedbackType` follows from the same reasoning: going
through `LocalView` reaches the full platform palette today, and a BOM bump is a change with a
blast radius far wider than this feature.

### 5. API-34 constants are guarded, and degrade rather than disappear

`TOGGLE_ON` / `TOGGLE_OFF` are the natural mapping for `Toggle` and are unavailable on the API 33
floor. The dispatcher guards on `Build.VERSION.SDK_INT` and falls back to a supported constant —
never to nothing. This mirrors `ReducedMotion.kt`'s standing instruction that callers "degrade to a
*static* cue, never to nothing": a device that cannot deliver the precise effect still delivers
*an* effect, so the interaction does not feel inert on older hardware.

### 6. A `HapticPlayer` seam, so the mapping is testable off-device

The dispatcher depends on a narrow `HapticPlayer` interface rather than calling the view directly.
The real implementation wraps `LocalView`; the test implementation records intents. That makes
"applying a restore emits `Confirm` exactly once" and "`StreakBroken` emits nothing" assertable in
JVM unit tests.

Tests cover the earned and committed tiers only. Nobody should write a test asserting that a
navigation tab is silent — silence is the default, and testing a default is testing the framework.

### 7. Earned intents are bound to the event stream, not to ui state

`ProgressEventHaptics` maps `ProgressEvent` → `HapticIntent` in one exhaustive `when`. The Home
consumer that presents an event plays its intent at the moment of presentation, then acknowledges.

This is the compile-time hook the pipeline needs: adding a fifth progress event in some future
change breaks this `when`, and the author must decide what it feels like before the code builds.
Combined with `RecomputeSource` on `persist` from `add-progress-events`, a new kind of progress
cannot reach a player without two deliberate answers — was it earned, and what does it feel like.

## Risks / Trade-offs

- **`LevelUp` and `Confirm` may feel indistinguishable in the hand.** → Accepted for the first
  iteration, and isolated to one file. If it reads as flat on real hardware, the dispatcher is
  where composed primitives would land, with the permission cost weighed then against evidence
  rather than in advance.

- **The system touch-feedback setting is all-or-nothing.** A player who disables it to stop
  keyboard buzz also loses level-up feedback. → Accepted. An in-app override would require
  `VIBRATE` and would mean overriding a preference the player explicitly set, which is the wrong
  default for an app that otherwise honours system accessibility settings without argument.

- **Silence-by-default means a genuinely deserving action can be missed** — a new destructive
  confirmation added later gets no haptic and nothing complains. → Accepted as the correct trade.
  The alternative catches it only by making all 146 sites declarative, which reintroduces exactly
  the noise this is designed to avoid. The spec's requirement that committed actions carry an
  intent is the review-time check; the tier is small enough to hold in one's head.

- **Two changes must land in order.** The earned tier is dead code until `add-progress-events`
  ships. → The committed tier is fully independent and could ship alone if the first change
  stalls; the tasks are grouped so that split is available without restructuring.

- **`ui/util/` becomes the home for a second sensory authority.** → That is the intent. Placing
  `Haptics.kt` beside `ReducedMotion.kt` makes the pattern legible: cross-cutting sensory concerns
  live in one file each, with their contract in the KDoc and a grep in `CLAUDE.md`. The retrofit of
  that grep onto `ReducedMotion.kt` is out of scope here but is the obvious follow-up.
