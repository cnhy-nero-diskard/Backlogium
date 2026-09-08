# app-ui

## ADDED Requirements

### Requirement: The collection save action is refused for a blank name
When a collection editor's name is empty or contains only whitespace, the save action SHALL be
unusable — not merely styled as unavailable. Activating it SHALL NOT persist a collection and
SHALL NOT navigate away as though a save had succeeded.

The refusal SHALL be enforced below the presentation layer as well as in it. A view model or
equivalent SHALL reject a blank name independently of any visual state, so a caller that does
not go through the screen — a test, or another entry point added later — cannot store a
nameless collection.

A dimmed or recoloured control that still invokes its action is specifically insufficient: it
communicates the constraint without applying it, which produces a stored collection the user
was told they could not create.

#### Scenario: Save is unusable while the name is blank
- **WHEN** the collection editor's name field is empty or whitespace-only
- **THEN** the save action cannot be activated

#### Scenario: Activating save with a blank name stores nothing
- **WHEN** the save action is invoked while the name is blank, by any route
- **THEN** no collection is created or updated, and the editor does not navigate away

#### Scenario: Whitespace-only names are treated as blank
- **WHEN** the name contains only spaces or other whitespace
- **THEN** it is refused exactly as an empty name is

#### Scenario: The invariant does not depend on the screen
- **WHEN** a save is requested with a blank name without going through the editor's controls
- **THEN** it is still refused

#### Scenario: A named collection saves normally
- **WHEN** the name is non-blank
- **THEN** the save action is usable and the collection is stored as before
