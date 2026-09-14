## ADDED Requirements

### Requirement: Cloud presence section

The Settings destination SHALL present a cloud presence section that lets the user configure an
endpoint and credential, shows whether the configuration is active and when it was last read
successfully, and lets the user remove it.

The section SHALL disclose that cloud presence adds a record of when play happened and that the
app functions fully without it, so enabling it reads as an addition rather than a repair of
something broken.

#### Scenario: Not configured

- **WHEN** the section is shown with nothing configured
- **THEN** it offers configuration and states what the feature adds
- **AND** it presents no error and no failed state

#### Scenario: Configuration is verified before it is accepted

- **WHEN** the user submits an endpoint and credential
- **THEN** the values are checked against the endpoint before being stored
- **AND** values that fail verification are not stored

#### Scenario: Account mismatch is refused with a stated cause

- **WHEN** verification succeeds but the endpoint reports a different Steam account than the one
  configured
- **THEN** the configuration is not stored
- **AND** the section states that the endpoint is polling a different account and which one the app
  expects

#### Scenario: Configured and healthy

- **WHEN** the section is shown while configured and a read has succeeded
- **THEN** it shows the active endpoint, a masked credential, and when the last successful read
  occurred

#### Scenario: Configured but failing

- **WHEN** reads have been failing
- **THEN** the section states that, and when the last successful read occurred, rather than
  presenting the configuration as healthy

#### Scenario: Removing the configuration

- **WHEN** the user removes the configuration
- **THEN** the stored credential is destroyed and the section returns to its unconfigured state
- **AND** no recorded play is removed, because this phase records none
