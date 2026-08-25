## Purpose

Defines distinct, recognizable, and independently operable Android identities for development and published Backlogium builds installed on the same device.

## Requirements

### Requirement: Debug and release builds install side by side
The debug build SHALL use a different Android application identity from the release build. The release build SHALL retain `com.example.backlogium`, and changing the debug identity SHALL NOT change the application code namespace.

#### Scenario: Install debug beside release
- **WHEN** a device already has the release build installed and the developer installs a debug build
- **THEN** Android retains both applications as independently launchable installations

#### Scenario: Upgrade an existing release
- **WHEN** a newer correctly signed release build is installed
- **THEN** Android treats it as an upgrade of the existing release application rather than as a new application

#### Scenario: Replace only the debug installation
- **WHEN** a newer debug build is installed over an existing debug build
- **THEN** Android replaces only the debug installation and leaves the release installation unchanged

### Requirement: Debug builds are visibly distinguishable
The debug build SHALL be identified as `Backlogium Debug`, SHALL expose a debug-suffixed version name, and SHALL use a launcher icon with a high-contrast `DBG` badge. The badge geometry SHALL remain recognizable in adaptive, round, and monochrome or themed icon presentation so identification does not depend on color alone. The release label and launcher icon SHALL remain unchanged.

#### Scenario: Identify both apps in the launcher
- **WHEN** debug and release builds are installed together
- **THEN** the launcher presents the debug build with its debug label and badge while presenting the release build with its existing identity

#### Scenario: Identify a themed debug icon
- **WHEN** the device applies monochrome or themed launcher icons
- **THEN** the debug icon retains recognizable badge geometry distinct from the release icon

#### Scenario: Inspect the debug version
- **WHEN** the running debug build displays or reports its version name
- **THEN** the version name includes a debug suffix

### Requirement: Variant application state is isolated
Debug and release builds SHALL maintain separate application data, credentials, permissions, notifications, scheduled work, caches, and backup state. The system SHALL NOT automatically copy or migrate state between variants; an explicit user-driven export and import MAY be used where existing backup functionality permits it.

#### Scenario: Configure the debug build
- **WHEN** a user changes credentials, settings, or local data in the debug build
- **THEN** the release build's state remains unchanged

#### Scenario: Remove one variant
- **WHEN** a user uninstalls either the debug or release build
- **THEN** the other installation and its application state remain available

### Requirement: Development workflows target the debug identity
Repository-maintained debug installation, launch, and instrumentation workflows SHALL target the debug application identity without relying on the release application ID as the installed target. Source and test class package names SHALL continue to use the unchanged code namespace.

#### Scenario: Install and launch from the device workflow
- **WHEN** the run-on-device workflow completes a successful debug installation
- **THEN** it launches the newly installed debug application rather than the release application

#### Scenario: Run debug instrumentation tests
- **WHEN** debug instrumentation tests execute on a device
- **THEN** package-identity assertions resolve against the actual debug target application ID

### Requirement: Existing variant behavior remains intact
Separating application identities SHALL NOT enable release-only self-update behavior in debug builds or disable the debug build's existing development configuration and normal application workflows.

#### Scenario: Run update scheduling in debug
- **WHEN** the debug build starts or runs scheduled work
- **THEN** release self-update checks and prompts remain disabled as they were before identity separation

#### Scenario: Use normal debug app workflows
- **WHEN** the debug build is configured and used independently of the release build
- **THEN** its existing library, sync, background-work, diagnostics, and testing behavior remains available subject to existing debug-specific gates