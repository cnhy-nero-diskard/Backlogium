# Desktop Agent

## Why

Backlogium knows what you own, how long you have played it, what it would take to finish it, and
which game it thinks you should play next. It does not know the one thing that decides whether you
can act on any of that tonight: **which of those games are actually on the disk right now.**

The Steam Web API cannot answer that. Ownership, playtime, achievements, and presence are all
exposed; installed state is not, at any endpoint. It exists only on the machine itself, in
`libraryfolders.vdf` and the `appmanifest_<appid>.acf` files beside it.

That gap undercuts the app's most opinionated surfaces. A goal game the player cannot start without
a 90 GB download is not a goal for tonight. "3 of your backlog are ready to play now" is a filter
no Steam surface offers well and this app is otherwise perfectly positioned to give.

Closing it requires something running on the machine. That is a real cost — a new artifact, a new
toolchain, a pairing model — and it is the reason to do it deliberately and read-only first. A
component that *reports* is a much smaller thing to get right than one that *acts*, and the
pairing, discovery, and trust it establishes are the foundation a later command channel would need
regardless.

## What Changes

- **A new `agent/` component**: a small Go daemon for Windows that advertises itself on the local
  network, pairs with one phone, and reports the local Steam installation state. Single static
  binary, tray icon, autostart, no installer.
- **Discovery over mDNS** as `_backlogium._tcp`, with **manual host entry as a first-class
  fallback** — AP isolation and guest VLANs break multicast, and a discovery-only design would be
  intermittently broken on real home networks.
- **One-time PIN pairing** establishing a shared secret and pinning the agent's self-signed
  certificate on trust-first-use. The secret lives in the existing Keystore-backed
  `EncryptedCredentialStore`, beside the Steam key.
- **Read-only protocol.** The agent exposes a greeting and an installed-set report. It accepts no
  command that changes anything on the host. A later change adds the launch verb, deliberately not
  this one.
- **Installed state in the app**: which owned games are installed, their size on disk, and when the
  report was last received — surfaced on game detail and as a Library filter.
- **The feature is absent until paired.** No pairing, no UI, no behaviour change. The app remains
  fully functional with no agent, no LAN, and no network.

## Capabilities

### New Capabilities
- `desktop-agent`: the agent's identity, discovery, pairing and trust model, the transport's
  integrity guarantees, and the rule that the agent never acts on the host — including what happens
  when the agent is unreachable, unpaired, or reporting stale data.
- `local-library-state`: what installed state means, how it is reported and stored, how staleness
  is expressed rather than hidden, and how the app behaves when the local view and the Steam-owned
  view disagree.

### Modified Capabilities
- `app-settings`: a paired-desktop section — pair, view connection status, unpair.
- `app-ui`: game detail states whether a game is installed; the Library gains a ready-to-play
  filter.

## Impact

- **A third toolchain in the repo.** `agent/` is Go, built with `go build`, and is invisible to
  Gradle — `settings.gradle.kts` includes only `:app` and `:gamification`, so the isolation matches
  the precedent `functions/` already set. `CLAUDE.md`'s build table gains a third row.
- **Go rather than TypeScript**, despite `functions/` establishing a Node toolchain. The agent is a
  background daemon on a Windows desktop: Go produces one static binary with no runtime dependency,
  where a Node build needs either a preinstalled Node or a bundled executable that is an order of
  magnitude larger and reliably trips Defender heuristics. Consistency inside the repo is not worth
  paying for on the target machine — and Go keeps a future SteamOS build a recompile rather than a
  rewrite.
- **Affected code (new, agent):** `agent/cmd/backlogium-agent/`, plus `internal/steam` (parsing
  `libraryfolders.vdf` and `appmanifest_*.acf`), `internal/pair` (PIN, HMAC, self-signed cert),
  `internal/discovery` (mDNS), and `agent/README.md` mirroring `functions/README.md`'s operational
  role.
- **Affected code (new, app):** a `data/agent/` client (discovery, pairing, fetch), a Room table for
  installed state, a repository exposing it as domain models, and the settings pairing surface.
- **Affected code (modified):** game detail and Library, to show and filter on installed state.
- **A Room migration** for the installed-state table. It is derived, host-owned data that can always
  be re-fetched, so it is safe to drop and rebuild rather than migrate carefully.
- **Windows-only for now.** Every host-specific assumption — registry lookup for the Steam path,
  path conventions, autostart — sits behind one interface so SteamOS remains an open decision.
- **Personal use, so no code signing and no installer.** The binary is built locally and started
  from the Startup folder. SmartScreen's unsigned-binary warning is accepted rather than paid off.
- **No cloud.** The agent speaks only to the phone over the LAN. Firestore is untouched, and the
  standing decision that client Firestore access stays denied is not disturbed.
- **The engine invariant is untouched.** Installed state is observed host data, not a derived
  value. Nothing here computes sessions, playtime, or XP.
