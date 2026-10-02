## ADDED Requirements

### Requirement: Game detail exposes a favorite heart
Every tracked-game detail entry point SHALL offer an accessible, labeled favorite heart reflecting committed favorite state. It SHALL identify the action as Add to favorites or Remove from favorites, prevent conflicting repeated writes while pending, and preserve the prior state with visible retry feedback on failure. It SHALL remain usable offline and at large font scale.

#### Scenario: Detail opened from different surfaces
- **WHEN** the same game is opened from Library, Home, or a collection
- **THEN** each detail presentation shows the same favorite state and toggle behavior

#### Scenario: Preference write fails
- **WHEN** toggling a favorite fails
- **THEN** the heart retains the last committed state and an inline message or snackbar offers a retry

### Requirement: Library provides a quick collection picker
Library SHALL offer a labeled Add to collection action for visible tracked games in list, grid, and compact grid. The picker SHALL list existing editable custom collections, identify existing membership, and prevent duplicate additions. Wishlist-only results SHALL NOT receive favorite or collection controls. Closing or completing the picker SHALL retain the current Library visit's query, filters, density, and scroll context under the existing visit policy.

#### Scenario: Action in each density
- **WHEN** a tracked game is shown in any Library density
- **THEN** the collection picker is reachable without opening the full collection editor or replacing achievement-selection long-press behavior

#### Scenario: Existing membership
- **WHEN** the picker opens for a game already in a collection
- **THEN** that collection is marked as already containing it and adding again cannot reset its position or done mark

#### Scenario: No collection exists
- **WHEN** the picker has no eligible custom collections
- **THEN** it explains the empty state and offers the existing collection-creation route while preserving Library visit context

#### Scenario: Wishlist-only result
- **WHEN** a Library search match exists only on the wishlist
- **THEN** its existing store action remains available without tracked-game collection controls

### Requirement: Custom overview supports direct member removal
A custom collection overview SHALL provide an accessible Remove from this collection action for each visible member with clear immediate committed-state feedback. It SHALL distinguish membership removal from Hide game or collection deletion, preserve direct game-detail navigation, and SHALL NOT add membership controls to derived collection overviews.

#### Scenario: Member removed from the overview
- **WHEN** a user removes a visible member successfully
- **THEN** the overview and its summary update and identify which collection membership was removed

#### Scenario: Derived overview
- **WHEN** a derived collection is opened
- **THEN** its members remain read-only and no Remove from collection action is offered
