# app-ui

## ADDED Requirements

### Requirement: Unconfigured-state guidance names the surface that hosts the action
Where a screen presents an unconfigured or empty state that tells the player to perform an
action elsewhere in the app, it SHALL name the surface that currently hosts that action.
Guidance SHALL NOT name a surface from which the control has been moved or removed.

This applies to every screen reachable while unconfigured. Bottom navigation remains
available before credentials are configured, so these states are not unreachable leftovers —
they are the first thing a new player reads.

Where such guidance can be reached, it SHALL be verifiable against the surface that actually
owns the control, so that moving an administration control does not silently leave a trail of
instructions to a place it no longer is.

#### Scenario: Steam account guidance names the account surface
- **WHEN** the Library, History, or Analytics screen is shown while Steam credentials are not
  configured
- **THEN** its guidance directs the player to the surface that currently hosts Steam account
  configuration, and does not direct them to Home

#### Scenario: Guidance is reachable and therefore load-bearing
- **WHEN** the app is opened without configured credentials
- **THEN** these screens are reachable through navigation, so their guidance is treated as
  product copy rather than as an unreachable remnant

#### Scenario: Home is not named as an administration surface
- **WHEN** any unconfigured-state guidance is presented
- **THEN** it does not instruct the player to configure an account from Home, which carries
  progress content only
