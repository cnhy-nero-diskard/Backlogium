# App UI Delta

## ADDED Requirements

### Requirement: Deadline archive is explicit and read-only
An eligible deadline overview SHALL offer Finish and archive with a final preview explaining the evidence, outcome and one-way transition. Completed archives SHALL be inspectable from Collections and absent from active collection views and pickers.

#### Scenario: Player cancels confirmation
- **WHEN** the archive preview is dismissed
- **THEN** the collection remains active and editable

#### Scenario: Player opens an archive
- **WHEN** a saved result is selected from archived collections
- **THEN** its deadline, members, estimate basis and frozen outcome are readable without active edit or membership actions
