# onboarding-credentials

## ADDED Requirements

### Requirement: Only a result for the displayed credential input may advance or persist
An in-flight SteamID resolution or credential verification SHALL be tied to the credential
input that started it. A result SHALL advance the flow, enable a save affordance, or reach
persistence only while the input it was started for is still the input the screen displays.
When the player edits the input, or leaves the step, any result still in flight for the
previous input SHALL be discarded and SHALL NOT be published.

The system SHALL NOT rely on the absence of a prior configured identity to catch this. On
first configuration there is no stored SteamID for a save to reject as an account change, so
nothing downstream will notice that the persisted account is not the one the player was
looking at.

Either request identity or control locking SHALL be used, and whichever is chosen SHALL cover
the whole operation. Resetting the visible state on edit is not sufficient by itself: the
visible reset does not stop a coroutine that already captured the earlier value.

#### Scenario: Input edited while resolution is in flight
- **WHEN** the player starts resolving one SteamID input, edits the field to a different value
  before the resolution completes, and the first resolution then succeeds
- **THEN** the earlier result is discarded, the flow does not offer to save, and no resolved
  identity from the earlier input is retained

#### Scenario: Save cannot persist an account that is not displayed
- **WHEN** a resolution completed for an input the screen no longer displays
- **THEN** completing the flow cannot persist that resolved account

#### Scenario: First configuration is protected too
- **WHEN** the situation above occurs on first configuration, with no previously stored
  SteamID
- **THEN** the stale account is still not persisted, even though no account-change check would
  reject it

#### Scenario: Input edited while verification is in flight
- **WHEN** the player edits the SteamID field or navigates back while verification is running,
  and that verification then succeeds
- **THEN** it does not persist credentials, because the values it captured are no longer the
  values the screen displays

#### Scenario: The displayed input's own result is honoured
- **WHEN** a resolution or verification completes for the input the screen still displays
- **THEN** it advances the flow normally, so the guard does not make the ordinary path
  unreliable

#### Scenario: Retry after a failure still works
- **WHEN** verification fails and the player retries with both entered values still in place
- **THEN** the retry proceeds and can persist on success
