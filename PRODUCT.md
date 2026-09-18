# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

The primary user is an individual Steam PC gamer who uses an Android phone to
manage a personal backlog, play habits, completion progress, and Steam library.
The product is intended for day-to-day personal use, including situations where
the phone is offline.

## Product Purpose

Backlogium is an offline-first Android companion for a Steam library. It makes
Steam data useful for backlog planning, playtime and completion tracking,
achievement progression, quests, streaks, collections, and analytics. Success
means the user can understand and act on their library from the phone while
retaining useful local functionality when network access is unavailable.

## Positioning

Backlogium keeps the product loop local and private: Steam and shared datasets
provide inputs, while the phone remains the source of the user's working
library, progress, settings, and backups. Sync, enrichment, and contribution
actions are explicit rather than prerequisites for using the app.

## Operating Context

- The user connects a Steam account, verifies the credentials, and optionally
  runs guided first-run setup for an initial sync, offline asset download, and
  shared HowLongToBeat dataset download.
- The app is used across five top-level destinations: Home, Library, History,
  Analytics, and Settings. Game detail, collections, HLTB review, onboarding,
  setup, and diagnostics are pushed surfaces.
- Steam data is fetched through the Steam Web API. Local persistence uses Room
  and Preferences DataStore; background work uses WorkManager.
- A Firebase Cloud Function independently polls Steam presence. The Android app
  currently does not consume that cloud presence log and does not depend on it
  to function.

## Capabilities and Constraints

- Supports Steam onboarding from an API key, SteamID64, or profile URL, with
  verification against Steam before credentials are saved.
- Syncs owned games, playtime, recent activity, profile data, achievements,
  achievement schemas, genres, and live presence.
- Provides XP, levels, quests, streaks, Focus games, Steam history import, and
  rarity-weighted achievement XP.
- Provides custom collections, derived collections, pacing and deadline views,
  HLTB completion estimates, analytics, live monitoring, diagnostics, local
  backup/restore, and in-app release updates.
- HLTB coverage comes primarily from a shared release-served dataset. Games
  outside coverage require deliberate per-game lookup; contribution export
  discloses that included Steam app IDs reveal owned games.
- Steam credentials are encrypted at rest with an Android Keystore-backed key.
  Steam-owned and app-owned fields are kept distinct, and sync persistence is
  designed to be atomic and concurrency-safe.
- Offline Steam assets are downloaded manually into app-private storage with an
  integrity-checked manifest; the download does not run as a side effect of
  sync or a schedule.
- The Android client is Kotlin with Jetpack Compose, Material 3, Hilt, Room,
  DataStore, and WorkManager. The repository also contains a standalone
  gamification JVM module and Firebase Cloud Functions in Node/TypeScript.
- The current cloud presence log has no Android or OBS consumer. An OBS overlay
  remains a roadmap item.

## Brand Commitments

- Product name: Backlogium.

## Evidence on Hand

- `README.md` documents the product loop, current destinations, supported
  workflows, architecture, data handling, and known roadmap boundaries.
- `CLAUDE.md` documents architectural constraints and security expectations.
- The implemented Android client is under `app/src/main`, with Firebase
  functions under `functions/src` and shared gamification under
  `gamification/src`.
- No testimonials, customer studies, or external product claims are available
  in the repository and future work must not fabricate them.

## Product Principles

- Keep the core experience useful offline and locally.
- Make sync, enrichment, publishing, and monitoring actions explicit and
  understandable.
- Preserve data ownership, provenance, privacy, and write integrity.
- Turn library data into actionable progress without obscuring the underlying
  Steam facts.
