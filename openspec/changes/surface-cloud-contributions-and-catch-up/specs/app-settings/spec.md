## ADDED Requirements

### Requirement: Cloud catch-up frequency can be understood and bounded

While the reader is configured, Settings SHALL offer Automatic as the initial routine cloud catch-up policy, selected when a reader is first verified and no policy has been persisted, and a choice of 12-hour, daily, or 48-hour minimum gaps. Automatic SHALL combine a daily background opportunity with play-end opportunities under a shared 12-hour minimum gap. A chosen override SHALL use its selected minimum gap across both routine triggers, while preserving Read now and accuracy-driven placement reads independently. The section SHALL explain that these choices limit routine phone reads, not the cloud poller's own minute-by-minute observation or the total number of all cloud requests. It SHALL show the last routine attempt and its outcome, the last successful read, and a next eligible opportunity where determinable without describing it as a guaranteed execution time.

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
