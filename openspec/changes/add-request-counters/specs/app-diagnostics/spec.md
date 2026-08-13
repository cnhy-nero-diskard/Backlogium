# app-diagnostics

## ADDED Requirements

### Requirement: Rolling request counters
The system SHALL maintain aggregated request counters in hourly buckets so that the number of
requests made during rolling windows — the last 24 hours, 30 days, and 365 days — can be reported
long after the individual sync run records that produced them have been pruned. Each bucket SHALL
record the API route, the response status, whether the requests succeeded, and how many requests
it holds, and SHALL NOT contain credential values in any form.

#### Scenario: Counters recorded at run finish
- **WHEN** a sync run finishes
- **THEN** each of its recorded requests is added to the hourly bucket of the run's start hour,
  keyed by the request's API route and status, with successful requests distinguished from
  unsuccessful ones

#### Scenario: Successful and unsuccessful split
- **WHEN** a request is folded into the counters
- **THEN** a request whose status is in the 2xx range is recorded as successful, and any other
  status — including a transport failure with no HTTP response — is recorded as unsuccessful

#### Scenario: Counters survive run pruning
- **WHEN** sync run records are pruned after reaching their retention limit
- **THEN** the request counters accumulated from those runs remain available

#### Scenario: Rolling windows measured from query time
- **WHEN** counters are requested for a window
- **THEN** the window is measured in elapsed time backwards from the query moment — the last 24
  hours, 30 days, or 365 days — and includes every bucket whose start falls within it

#### Scenario: Counters aggregated at route level
- **WHEN** counters are written or reported per endpoint
- **THEN** requests are grouped by their API route (host and path) without query parameters, so a
  route is identifiable without per-request parameter variants

#### Scenario: Counters contain no credentials
- **WHEN** counter records are stored or read back
- **THEN** no credential value appears in them, because route identifiers never carry query
  parameters

#### Scenario: Counter retention is bounded
- **WHEN** a counter bucket becomes older than the counter retention window of 400 days
- **THEN** it is pruned, so counter storage does not grow without limit

#### Scenario: Counter failure does not fail the run
- **WHEN** writing or pruning counters fails for any reason
- **THEN** the sync run it accompanied is unaffected

#### Scenario: Retained records are backfilled
- **WHEN** the app upgrades to the version introducing the counters
- **THEN** the retained sync run records and their request breakdowns are folded into the counter
  buckets, so counters begin with the history that was still retained rather than at zero
