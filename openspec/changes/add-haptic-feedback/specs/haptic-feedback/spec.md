## ADDED Requirements

### Requirement: A single haptic authority
The app SHALL express haptic feedback through one shared authority, which is the only code
permitted to invoke a platform haptic API. Call sites SHALL express an intent; the authority SHALL
own the mapping from intent to delivered effect, so the app gives one answer to how a given kind of
moment feels.

#### Scenario: A surface requests feedback
- **WHEN** any surface wants to deliver haptic feedback
- **THEN** it names an intent from the vocabulary and the authority selects the effect

#### Scenario: No surface reaches the platform directly
- **WHEN** the app's sources are searched for direct platform haptic calls outside the authority
- **THEN** none are found

#### Scenario: Retuning the app's feel
- **WHEN** the effect chosen for an intent is changed
- **THEN** every surface using that intent changes with it, with no call-site edits

### Requirement: A rationed haptic vocabulary
The app SHALL define a closed vocabulary of haptic intents covering earned progress — a level
gained, a daily quest met, a streak milestone reached, a streak lost — and committed actions — an
action with consequence succeeding, an action being refused or failing, and a binary state being
switched. The vocabulary SHALL also include an explicit intent meaning no feedback.

#### Scenario: An intent outside the vocabulary cannot be requested
- **WHEN** a surface attempts to express a moment not covered by the vocabulary
- **THEN** it has no intent to name, and the moment is silent

#### Scenario: Extending the vocabulary is deliberate
- **WHEN** an intent is added to the vocabulary
- **THEN** the authority's mapping must supply an effect for it before the app builds

### Requirement: Silence is the default
Surfaces not covered by the vocabulary SHALL be silent. The app SHALL NOT require a per-surface
declaration of silence, and an interactive surface that delivers no haptic feedback SHALL be
considered correct rather than incomplete.

#### Scenario: Navigation is silent
- **WHEN** the player moves between destinations, scrolls, or taps a list row, chip, tab, or
  expander
- **THEN** the app delivers no haptic feedback of its own

#### Scenario: A newly added control needs no declaration
- **WHEN** a new control is added to a screen and names no intent
- **THEN** it is silent, and this is a correct outcome requiring no annotation

### Requirement: A haptic never fires alone
Haptic feedback SHALL accompany a moment that is being presented to the player at the same time.
The app SHALL NOT deliver haptic feedback for a change the player cannot concurrently see.

#### Scenario: Earned progress produced while the app was closed
- **WHEN** a background sync produces a level-up and the player later opens the app
- **THEN** the haptic is delivered at the moment the level-up is presented, not before

#### Scenario: No unattributable feedback
- **WHEN** haptic feedback is delivered
- **THEN** a corresponding visible change is on screen at that moment

#### Scenario: A presented moment is not repeated
- **WHEN** a progress event has been presented and acknowledged
- **THEN** neither the presentation nor its haptic recurs

### Requirement: Progress events map exhaustively to intents
Every progress event SHALL have a declared haptic intent, and the mapping SHALL be exhaustive so
that a progress event added later cannot reach a player without its haptic answer being supplied.
A streak break SHALL map to no feedback: it is acknowledged visually, and the app SHALL NOT deliver
a haptic for losing progress.

#### Scenario: A level-up is felt
- **WHEN** a level-up event is presented
- **THEN** the earned level-up intent is delivered once

#### Scenario: A streak break is not felt
- **WHEN** a streak-broken event is presented
- **THEN** no haptic feedback is delivered, and the break is conveyed by its visible acknowledgement
  alone

#### Scenario: A new event type forces a decision
- **WHEN** a new progress event is introduced
- **THEN** the mapping does not compile until an intent, including explicitly none, is supplied
  for it

#### Scenario: Several events presented together
- **WHEN** more than one progress event is pending and one is presented
- **THEN** exactly one haptic is delivered, for the event actually presented

### Requirement: Committed actions carry an intent
Actions that commit a consequence SHALL deliver feedback: succeeding actions the success intent,
refused or failed actions the refusal intent, and binary switches the toggle intent. Merely
navigating to, opening, or previewing such an action SHALL NOT deliver feedback.

#### Scenario: A destructive action succeeds
- **WHEN** the player confirms a restore, saves a rule change, or deletes a snapshot
- **THEN** the success intent is delivered once, as the action lands

#### Scenario: An action is refused
- **WHEN** an action fails or is blocked, such as a sync that cannot complete
- **THEN** the refusal intent is delivered once

#### Scenario: A switch is flipped
- **WHEN** the player switches a binary setting, or enters or leaves a selection mode
- **THEN** the toggle intent is delivered once

#### Scenario: Opening a confirmation is silent
- **WHEN** the player opens a confirmation dialog without confirming it
- **THEN** no haptic feedback is delivered

#### Scenario: Cancelling is silent
- **WHEN** the player dismisses a confirmation without committing
- **THEN** no haptic feedback is delivered

### Requirement: Feedback degrades rather than disappears
Where a device cannot deliver the precise effect chosen for an intent, the authority SHALL
substitute a supported effect rather than delivering nothing, so an interaction is never inert on
account of platform version alone.

#### Scenario: An effect unavailable on an older platform version
- **WHEN** an intent's preferred effect requires a platform version newer than the device's
- **THEN** a supported effect is delivered in its place

#### Scenario: The substitution is invisible to call sites
- **WHEN** a surface names an intent
- **THEN** it is unaffected by which effect the device ends up delivering

### Requirement: The player's system preference governs
The app SHALL honour the platform's touch-feedback preference and SHALL NOT override it or provide
a means of bypassing it.

#### Scenario: Feedback disabled system-wide
- **WHEN** the player has turned off touch feedback in system settings
- **THEN** the app delivers no haptic feedback, and every affected moment remains fully conveyed by
  its visible presentation

#### Scenario: No permission required
- **WHEN** the app delivers haptic feedback
- **THEN** it does so without holding a vibration permission
