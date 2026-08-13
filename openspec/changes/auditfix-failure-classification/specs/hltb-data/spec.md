# hltb-data

## ADDED Requirements

### Requirement: Session re-resolution is triggered only by evidence of rotation or expiry
The system SHALL re-resolve the scraped session — its endpoint and token — only when a failure
indicates that the endpoint rotated or the token expired. Transport failures, server errors,
throttling, and unusable response bodies SHALL NOT trigger re-resolution, so a transient failure
does not multiply requests against the scraped service.

#### Scenario: Rejection indicating a stale session
- **WHEN** a search is rejected in a way that indicates a rotated endpoint or an expired token
- **THEN** the session is re-resolved once and the search retried once

#### Scenario: Transport failure
- **WHEN** a search fails because of a timeout, connection failure, or name-resolution failure
- **THEN** no re-resolution occurs and the failure is reported as transient

#### Scenario: Server error
- **WHEN** a search fails with a server-side error status
- **THEN** no re-resolution occurs and the failure is reported as transient

#### Scenario: Throttling
- **WHEN** a search is throttled
- **THEN** no re-resolution occurs and the failure is reported in a way that allows the caller to
  back off

#### Scenario: Unusable response body
- **WHEN** a search returns a successful response whose body cannot be interpreted
- **THEN** no re-resolution occurs, and the failure is reported as unlikely to succeed on retry

#### Scenario: Re-resolution happens at most once per search
- **WHEN** a search triggers a re-resolution and the retry also fails
- **THEN** no further re-resolution is attempted for that search

#### Scenario: Classification does not depend on message text
- **WHEN** a failure is classified
- **THEN** the classification uses structured information such as the response status, not the
  text of an error message

### Requirement: Batch outcomes reported reflect what actually happened
A batch refresh SHALL distinguish games that were refreshed, games for which no match exists,
and games whose lookup failed. Progress reported to the user on completion SHALL state the number
actually refreshed, and SHALL NOT report attempted work as refreshed work.

#### Scenario: Every lookup in a batch fails
- **WHEN** all lookups in a batch fail
- **THEN** the completion report states that none were refreshed

#### Scenario: Genuine absence of a match
- **WHEN** a lookup succeeds and establishes that no match exists
- **THEN** that game is reported as having no match rather than as a failure

#### Scenario: Progress still advances on failure
- **WHEN** a lookup fails partway through a batch
- **THEN** the attempted-progress count still advances, so a progress indicator continues and
  terminates

#### Scenario: Mixed batch
- **WHEN** a batch contains refreshed games, no-match games, and failures
- **THEN** the completion report states the refreshed count, not the batch size

### Requirement: A batch that accomplished nothing is not reported as successful work
The scheduler outcome for a batch refresh SHALL reflect whether the batch made progress, so that
a wholesale transient failure becomes eligible for retry and backoff rather than being recorded
as completed work.

#### Scenario: Wholesale transient failure
- **WHEN** no game in a batch was refreshed and at least one failure was transient
- **THEN** the batch is reported to the scheduler as needing retry

#### Scenario: Partial progress
- **WHEN** some games in a batch were refreshed
- **THEN** the batch is reported as complete, and the remainder is left to a later pass

#### Scenario: Failure unlikely to resolve on retry
- **WHEN** a batch fails wholesale in a way that retrying cannot fix
- **THEN** the batch is not retried, so the same failure is not repeated on a schedule

#### Scenario: Cancellation is not a failure
- **WHEN** a batch is cancelled
- **THEN** cancellation propagates rather than being classified as a lookup failure or a
  retryable outcome
