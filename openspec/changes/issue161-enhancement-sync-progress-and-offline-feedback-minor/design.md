# Design

## Context

The app has a shell sync cue, operation-scoped Steam receipts, durable setup attempts, and Settings manual feedback. Reconciliation currently publishes a running flag. Setup has a library stage that can include inline achievement work and Settings exposes the separate full achievement refresh.

## Goals / Non-Goals

Show truthful progress without changing scheduling, post-play behavior, or the automatic work that setup owns. Keep offline browsing and local edits usable.

## Decisions

### Expose two operation views from existing execution

Extend worker progress and terminal result mapping using operation and account identity. Publish initial phase, then counts based on the actual fixed worklist of each phase. Throttle frequent progress writes and flush the terminal result. Preserve existing poll receipts and derive operation cancellation and constraint status from observable states. A periodic job waiting for its next run should stay idle in the shell.

### Use a single connectivity observation

Observe validated network capability at the shell lifecycle boundary. Initial unknown should not flash Offline. Propagate presentation state to pushed screens without injecting network probes into local actions or using a Steam failure as a reachability test.

### Audit action feedback at the owning surface

Check Library, detail, collections, HLTB review, onboarding, backup/restore and Settings. Keep existing clear feedback and ensure each missing result uses the current visual pattern and localization. Reuse `ManualSyncFeedbackTracker` or its existing acknowledgement contract to avoid an inline result plus repeated transient delivery.

## Risks / Trade-offs

Constraints and retry reasons can be unavailable from WorkManager. Preserve a neutral queued state in that case. Progress counts reflect processable attempts, and partial outcomes must preserve existing retry and setup readiness decisions.

## Migration Plan

Extend progress payloads additively. Interrupted work created by an older build may publish only its coarse status. Refresh affected visual baselines with the corresponding UI changes.
