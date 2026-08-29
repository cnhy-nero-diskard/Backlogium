## ADDED Requirements

### Requirement: Derived collections in the Collections screen
The Collections screen SHALL present derived collections as a group distinct from custom
collections, so the two are never mistaken for one another. Opening one SHALL show its members
using the same presentation custom collections use, without offering any management action.

#### Scenario: Both kinds present
- **WHEN** the player has custom collections and derived collections with members
- **THEN** both appear, visually grouped so that which is which is unambiguous

#### Scenario: Viewing members
- **WHEN** the player opens a derived collection
- **THEN** its member games are presented as they are in a custom collection, and each game remains
  openable

#### Scenario: No management affordances
- **WHEN** a derived collection is open
- **THEN** nothing is offered to add, remove, reorder, rename, restyle, or delete it

#### Scenario: Custom collection behaviour unchanged
- **WHEN** derived collections are present
- **THEN** custom collections' creation, ordering, modes, accents, and management behave exactly as
  they do today

### Requirement: Derived collections in the Home collections section
The Home screen SHALL present derived collections as a group beneath the custom collections,
separated by a horizontal dashed rule and always last in the section. Each SHALL show its name, its
member count, and its rule. They SHALL carry no accent, no mode banner, and no reordering gesture,
and the custom collections' existing drag-to-reorder behaviour SHALL be unchanged.

#### Scenario: Grouped below the custom cards
- **WHEN** Home is shown with both kinds present
- **THEN** the derived group appears below the custom cards, after a dashed rule, and which is which
  is unambiguous

#### Scenario: No reordering affordance
- **WHEN** the player presses and holds a derived collection card on Home
- **THEN** it is not picked up, and the custom cards' reordering is unaffected

#### Scenario: Opening one from Home
- **WHEN** the player taps a derived collection card on Home
- **THEN** the same read-only list the Collections screen opens is shown

### Requirement: Derived collection presentation
Each derived collection SHALL present its name, its member count, and the rule that determines
membership including its thresholds. It SHALL NOT present a deadline countdown, queue sequencing,
or a collection-level goal banner.

#### Scenario: Rule readable alongside the list
- **WHEN** a derived collection is presented
- **THEN** its rule and thresholds are readable without leaving the screen

#### Scenario: Member count shown
- **WHEN** a derived collection has members
- **THEN** the count is shown

#### Scenario: Completion basis disclosed
- **WHEN** the completed-games collection is viewed
- **THEN** each member indicates whether achievements or playtime determined it

#### Scenario: Missing data explained
- **WHEN** a derived collection depends on HowLongToBeat lengths and games are absent for want of
  them
- **THEN** the screen conveys that games without a length cannot appear, rather than implying none
  qualify

### Requirement: Derived collection visibility control
The Collections screen SHALL let the player hide and unhide each derived collection individually,
from the screen where the effect is visible.

#### Scenario: Hiding from the Collections screen
- **WHEN** the player hides a derived collection
- **THEN** it disappears from the screen and the choice persists across restarts

#### Scenario: Hidden lists remain reachable to unhide
- **WHEN** derived collections have been hidden
- **THEN** the player can still reach a control that restores them

#### Scenario: Visibility does not affect custom collections
- **WHEN** every derived collection is hidden
- **THEN** custom collections are presented exactly as they are today
