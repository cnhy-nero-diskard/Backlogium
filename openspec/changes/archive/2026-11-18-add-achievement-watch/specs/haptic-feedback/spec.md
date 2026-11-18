## ADDED Requirements

### Requirement: An unlock group is felt only with its foreground presentation
A foreground unlock group SHALL map to one restrained unlock intent through the central haptic authority, lighter than a level-up. No watcher, fetch, or background dispatcher SHALL emit a direct in-app haptic. System settings and independent alert controls SHALL govern feedback; background vibration SHALL use the notification channel.

#### Scenario: Multiple unlocks in one group
- **WHEN** a group of several achievements is shown
- **THEN** one haptic accompanies that presentation

#### Scenario: Unchanged or deferred read
- **WHEN** a read has no new group or is deferred
- **THEN** it produces no haptic

#### Scenario: Background notification
- **WHEN** an unlock group is dispatched as a notification
- **THEN** channel/system vibration controls apply and the foreground haptic authority is not invoked

## MODIFIED Requirements

### Requirement: A rationed haptic vocabulary
The app SHALL define a closed vocabulary of haptic intents covering earned progress - a level
gained, an achievement unlock group observed, a daily quest met, a streak milestone reached, a streak lost - and committed actions - an
action with consequence succeeding, an action being refused or failing, and a binary state being
switched. The vocabulary SHALL also include an explicit intent meaning no feedback.

#### Scenario: An intent outside the vocabulary cannot be requested
- **WHEN** a surface attempts to express a moment not covered by the vocabulary
- **THEN** it has no intent to name, and the moment is silent

#### Scenario: Extending the vocabulary is deliberate
- **WHEN** an intent is added to the vocabulary
- **THEN** the authority's mapping must supply an effect for it before the app builds
