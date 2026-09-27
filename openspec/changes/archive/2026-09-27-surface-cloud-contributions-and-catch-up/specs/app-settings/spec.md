## ADDED Requirements

### Requirement: Cloud catch-up frequency can be understood and bounded

While the reader is configured, Settings SHALL offer Automatic as the initial routine cloud catch-up policy, selected when a reader is first verified and no policy has been persisted, a choice of 12-hour, daily, or 48-hour minimum gaps, and an explicit Off / Manual only choice. Automatic SHALL combine a daily background opportunity with play-end opportunities under a shared 12-hour minimum gap. A chosen cadence SHALL use its selected minimum gap across both routine triggers. Off / Manual only SHALL disable those routine triggers without removing the reader or disabling Read now and accuracy-driven placement reads. The section SHALL state the initial Automatic behavior and explain that its cadence choices limit routine phone reads, not the cloud poller's own minute-by-minute observation or the total number of all cloud requests. It SHALL show the last routine attempt and its outcome and the last successful read; a next eligible opportunity SHALL appear only for an enabled cadence when determinable and SHALL NOT be described as a guaranteed execution time.

#### Scenario: First verified reader
- **WHEN** a reader is first verified and no routine policy has been persisted
- **THEN** Automatic is selected and the meaning of its routine catch-up is available in Settings

#### Scenario: Existing reader upgraded without a policy
- **WHEN** an app starts or upgrades with an already verified reader that has no persisted routine policy
- **THEN** Automatic is initialized without requiring the player to verify the reader again
- **AND** the initial verification-order watermark is seeded for routine admission
- **AND** existing cloud read and ingest positions are preserved

#### Scenario: Replacing a verified reader
- **WHEN** the configured reader is replaced by an endpoint successfully verified for the active Steam account
- **THEN** the selected cadence and shared minimum-gap cooldown are preserved
- **AND** routine work is scheduled for the replacement only when that existing cooldown allows it

#### Scenario: Choosing a slower cadence
- **WHEN** the player selects daily or 48 hours
- **THEN** that minimum gap is persisted for routine background and play-end reads and the displayed eligibility reflects it

#### Scenario: Connected reader used manually only
- **WHEN** the player selects Off / Manual only for a configured reader
- **THEN** Settings keeps the reader connected and Read now available, indicates that routine background and play-end catch-up are off, and does not show a next routine eligibility time

#### Scenario: Manual-only choice survives restart and replacement
- **WHEN** the player restarts the app or successfully replaces the reader for the same Steam account after choosing Off / Manual only
- **THEN** that choice remains selected rather than being interpreted as a missing policy and reset to Automatic

#### Scenario: Re-enable a cadence
- **WHEN** the player switches from Off / Manual only to an enabled cadence
- **THEN** the chosen cadence is displayed without promising an immediate read or resetting the last admitted attempt

#### Scenario: Existing placement needs a read
- **WHEN** the player selects a slower routine cadence
- **THEN** Settings explains that a Steam sync may still read cloud evidence to place delayed play accurately

#### Scenario: Routine read is partial or failed
- **WHEN** an attempt stops before reaching the end of unread history or fails
- **THEN** Settings reports partial progress or failure without describing the reader as caught up

#### Scenario: Unconfigured or offline
- **WHEN** no cloud reader is configured
- **THEN** no routine frequency control or cloud error is shown
- **AND** Settings remains usable offline from local state
