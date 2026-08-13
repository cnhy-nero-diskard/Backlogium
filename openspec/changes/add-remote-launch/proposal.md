# Remote Launch

## Why

Backlogium decides what you should play next — goal games, daily quests, streaks, the backlog
itself — and then asks you to go and do it yourself. With `add-desktop-agent` the app also knows
which of those games are sitting installed on the desktop in the next room. The remaining step is
small and obvious: start it, so the game is loaded by the time you sit down.

That is the whole scenario, and its narrowness is the point. This is not remote control, not
streaming, not "play from anywhere." It is the thirty seconds between deciding and sitting down.

Nothing in the Steam Web API can do it. Ownership, playtime, achievements, and presence are all
readable; there is no launch endpoint at any tier. Valve operates a device-to-device command
channel — the mobile app can trigger a download on a PC — and keeps it entirely private. The only
workable path is the machine's own `steam -applaunch`, reached through software already running
there. `add-desktop-agent` puts that software there, deliberately without any verb that acts.

This change adds exactly one verb.

## What Changes

- **A launch operation on the agent**, the first and only operation it exposes that changes
  anything on the host. It runs Steam's own launch command for a given app.
- **Launchable means installed, verified on the host.** The agent refuses any app without an
  installation record in a configured Steam library. This is both the correctness check — an
  uninstalled game cannot start — and the security boundary: Steam's non-Steam shortcuts are
  arbitrary executables registered under an appid, and they carry no installation record. The check
  that makes the feature work is the same check that stops it being a general-purpose way to run
  programs.
- **A Play on Desktop action** on game detail, present only when an agent is paired, reachable, and
  reporting the game as installed.
- **Two-stage confirmation.** The agent acknowledges immediately that Steam accepted the command;
  the launch is only *confirmed* when the app observes the desktop actually in that game, through
  the presence machinery already shipped. The app never claims a game is running on the agent's
  word alone.
- **Awake-only, stated rather than worked around.** No Wake-on-LAN. An unreachable desktop makes
  the action unavailable and says why.

## Capabilities

### New Capabilities
- `remote-launch`: what may be launched and what may not, the two-stage acknowledgement and
  confirmation model, what the app claims at each stage, and how the attempt resolves when
  confirmation never arrives.

### Modified Capabilities
- `desktop-agent`: the agent gains its first operation that acts on the host, replacing the
  requirement that it never does so. The constraint is narrowed rather than dropped — it acts only
  through Steam, and only on apps Steam reports installed.
- `app-ui`: game detail gains the Play on Desktop action and its pending state.

## Impact

- **Depends on `add-desktop-agent`.** Pairing, certificate pinning, request signing, replay
  rejection, and the installed report all come from there. This change adds a verb to an
  established trust model rather than establishing one alongside a dangerous operation.
- **Affected code (agent):** a launch handler; a launch path in `internal/steam` locating the Steam
  executable and invoking `-applaunch`; the installation-record gate reusing the manifest parsing
  already written for reporting.
- **Affected code (app):** a launch call on the agent client; launch state on `GameDetailViewModel`
  combining acknowledgement with observed presence; the action and its pending presentation.
- **Reuses the existing presence path for confirmation.** Live status already resolves what game
  the player is in. Confirmation is a matter of watching it for the expected app rather than
  building a status channel, and it is stronger than a self-report: the agent claims it ran a
  command, presence proves a game is running.
- **No new permission, no new persistence, no migration, no cloud.**
- **The engine invariant is untouched.** Launching produces no derived values. Any session that
  results is detected by the existing on-device engine exactly as if the game had been started by
  hand — which, from Steam's perspective, it was.
- **Windows only, matching the agent.** SteamOS remains an open decision; the launch path sits
  behind the same host interface as everything else OS-specific.
