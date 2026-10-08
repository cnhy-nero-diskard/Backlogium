## ADDED Requirements

### Requirement: App bottom navigation visibility
Backlogium SHALL show its bottom navigation bar only on the five top-level destinations: Home,
Library, History, Analytics, and Settings overview. First-run configuration and all pushed
destinations SHALL hide the app bar while retaining system navigation and system Back. Top-level
destinations SHALL remain reachable before Steam credentials are configured.

#### Scenario: Top-level destination
- **WHEN** the player visits Home, Library, History, Analytics, or Settings overview
- **THEN** the app bottom navigation is visible with the corresponding destination selected

#### Scenario: First-run configuration
- **WHEN** first-run credential onboarding or the setup step is shown
- **THEN** the app bottom navigation is hidden and system Back remains available

#### Scenario: Pushed destinations
- **WHEN** the player opens game detail, HLTB review, collection or derived collection, gap planning,
  Settings detail, diagnostics, cloud activity, or another pushed destination
- **THEN** the app bottom navigation is hidden

#### Scenario: Return to a top-level destination
- **WHEN** the player returns from a pushed destination
- **THEN** the app bottom navigation reappears for the restored top-level destination and its tab
  state remains intact

#### Scenario: Unconfigured top-level guidance remains reachable
- **WHEN** Steam credentials have not been configured and the player visits a top-level destination
- **THEN** the app bottom navigation remains available so the destination's setup guidance is reachable

### Requirement: Library bottom content spacing
The Library SHALL use the space above the app bottom navigation without an unexplained blank strip.
It SHALL respect the system navigation inset and keep the final list or grid item reachable. Hiding
or showing the app bar SHALL not leave stale padding or apply bottom space twice.

#### Scenario: Library list and grids
- **WHEN** Library is shown in list or either grid density
- **THEN** content reaches the intended space above the app bar and the last item can be fully seen
  and selected

#### Scenario: System navigation modes
- **WHEN** the device uses gesture navigation or three-button navigation
- **THEN** Library content and the app bar respect the system safe area without an extra blank band

#### Scenario: Bar transition
- **WHEN** the player opens a pushed screen from Library and returns
- **THEN** bottom spacing follows the visible app bar without retaining the prior screen's padding

#### Scenario: Display variations
- **WHEN** Library is shown in either theme or with increased font size
- **THEN** the final item remains reachable without overlap or an unexplained bottom gap
