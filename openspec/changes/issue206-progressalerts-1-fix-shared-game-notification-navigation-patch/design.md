# Design

## Context

`SharedGameNotifier` launches `MainActivity` without a game destination. `AchievementUnlockNotifier` already emits an account/game destination, and root navigation already validates it. Verify the reported behavior on the current baseline before editing.

## Goals / Non-Goals

Make shared-admission taps reach the right eligible game. Preserve update intent handling and existing achievement notification behavior.

## Decisions

### Use one validated game-destination path

Generalize the existing achievement intent decoding and navigation handoff for both producers. Pass the originating account identity and app ID from the committed admission. Use immutable PendingIntents with a distinct full identity for each target. Carry pending navigation until the startup gate and account validation are ready, then consume it once. A second route would duplicate account and navigation checks.

### Retain release compatibility

Accept the existing achievement intent payload from already posted notifications. Tap an old shared-admission notification with no target through its existing ordinary app-launch path. Revalidate game visibility and account immediately before opening and clear pending requests on account reset.

## Risks / Trade-offs

An Activity recreation can reuse its original intent, so an explicit consumption boundary is required. The containment for stale notifications is safe existing navigation with a normal Back stack.

## Migration Plan

Introduce the game target compatibly without modifying storage. Add screenshot baselines only if the navigation change alters a captured surface.
