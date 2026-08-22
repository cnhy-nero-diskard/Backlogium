## MODIFIED Requirements

### Requirement: A rationed haptic vocabulary
The app SHALL define a closed vocabulary of haptic intents covering earned progress - a level
gained, a daily quest met, a streak milestone reached, an achievement unlocked, a streak lost - and
committed actions - an action with consequence succeeding, an action being refused or failing, and a
binary state being switched. The vocabulary SHALL also include an explicit intent meaning no feedback.

The achievement-unlock intent SHALL be lighter than the level-up intent. An unlock is by a wide margin
the most frequent earned moment the app can present, and delivering it at a level-up's weight would
make the rarest moment indistinguishable from the most common one.

#### Scenario: An intent outside the vocabulary cannot be requested
- **WHEN** a surface attempts to express a moment not covered by the vocabulary
- **THEN** it has no intent to name, and the moment is silent

#### Scenario: Extending the vocabulary is deliberate
- **WHEN** an intent is added to the vocabulary
- **THEN** the authority's mapping must supply an effect for it before the app builds

#### Scenario: An unlock is not felt as a level-up
- **WHEN** an achievement unlock and a level-up are each presented
- **THEN** the two deliver distinguishable effects, with the unlock the lighter of the two

## ADDED Requirements

### Requirement: An unlock event is felt once per event, and only where it is seen
An achievements-unlocked event SHALL deliver the achievement-unlock intent once when it is presented
in the app, regardless of how many achievements it carries. Where the event is delivered as a
notification rather than presented in the app, the app SHALL deliver no haptic of its own.

#### Scenario: One haptic per event
- **WHEN** an unlock event carrying six achievements is presented in the app
- **THEN** the achievement-unlock intent is delivered once, not six times

#### Scenario: No haptic for a notification
- **WHEN** an unlock event is delivered as a notification while the app is not in view
- **THEN** the app delivers no haptic, consistent with the rule that a haptic never fires alone

#### Scenario: Presented alongside a higher-priority event
- **WHEN** a level-up is presented while an unlock event is also pending
- **THEN** exactly one haptic is delivered, for the event actually presented
