# onboarding-credentials

## ADDED Requirements

### Requirement: Repeatable credential editing from Settings
The system SHALL, once credentials are configured, present a credentials surface in the
Settings destination that shows the active SteamID and a masked API key and lets the user
reopen the onboarding flow to change credentials at any time.

#### Scenario: Reopening onboarding after configuration
- **WHEN** the user activates the "Edit" action on the Settings Steam account section
- **THEN** the onboarding flow reopens pre-reflecting the current state so credentials can be
  changed and re-saved

#### Scenario: Active credentials shown
- **WHEN** the Settings Steam account section is shown while configured
- **THEN** it displays the active SteamID and a masked form of the API key

#### Scenario: Home carries no account administration
- **WHEN** the Home screen is shown while configured
- **THEN** it presents no credentials card and no account edit affordance, because Home is
  progress-only

## REMOVED Requirements

### Requirement: Repeatable credential editing from Home
**Reason**: The administration surface moved to a top-level Settings destination. This
requirement retained pre-Settings wording and now contradicts `app-settings/spec.md:9-35`,
which makes Settings the account-management destination, and `app-ui/spec.md:190-206`, which
requires the Steam account card to be absent from Home. The shipped app follows Settings.

**Migration**: Replaced clause-for-clause by "Repeatable credential editing from Settings"
above. The SteamID display, masked-key display, and reopen-onboarding behaviours are
unchanged; only the surface hosting them differs. No stored data or user action is affected.
