## Context

`add-desktop-agent` establishes a paired, certificate-pinned, request-signed channel to a Go daemon
on the Windows host, and a read-only report of which owned games are installed. That agent
deliberately exposes no operation that acts on its host.

This change adds one. Everything below is about keeping it one.

The relevant givens:

- Steam exposes no remote launch at any API tier. `steam -applaunch <appid>` on the host is the
  mechanism, and it is a local one.
- The host is awake or the feature is unavailable. No Wake-on-LAN.
- The app already resolves what game the player is in, through `live-status` and the cloud presence
  poller.
- Steam's non-Steam shortcuts are arbitrary executables registered under an appid.

## Goals / Non-Goals

**Goals:**

- One verb, tightly bounded, on an already-reviewed trust model.
- The set of launchable things is exactly the set of installed Steam apps — no wider.
- The app never claims a game is running on the agent's say-so.
- The player can tell the difference between "did not start" and "cannot tell yet."
- The launch path is isolated behind the same host interface as the rest of the OS-specific code.

**Non-Goals:**

- Waking, sleeping, restarting, or otherwise controlling the host.
- Launching anything that is not an installed Steam app — no shortcuts, no arbitrary executables,
  no launch arguments.
- Queueing a launch for when the desktop next wakes.
- Stopping or closing a running game.
- Streaming or input forwarding.
- Any change to how sessions, playtime, or XP are detected.

## Decisions

### 1. The installation record is the security boundary

The agent refuses to launch any appid without an `appmanifest_<appid>.acf` in a configured Steam
library.

This is the whole answer to the obvious objection that a network-reachable launch endpoint is a
remote execution primitive. Non-Steam shortcuts — the mechanism by which an appid can point at an
arbitrary executable — have no manifest. Uninstalled games have no manifest. What remains is the
set of real Steam applications present on the disk.

The check is worth having twice over: it is also the correctness check, since launching an
uninstalled game does nothing useful. Collapsing "is this safe" and "will this work" into one file
existence test against records the agent already parses is why this is cheap.

*Alternative considered:* validate against the player's owned library from the Steam Web API.
Rejected — it admits family-shared and uninstalled titles, requires the agent to have network
access and credentials it otherwise does not need, and still does not exclude a shortcut registered
under an owned appid.

*Alternative considered:* an allowlist the player curates. Rejected as security theatre with real
friction: it constrains the player, not an attacker, since anything worth launching would be on it.

### 2. `steam.exe -applaunch <appid>`, not the `steam://` protocol handler

The agent already locates the Steam executable through the registry in order to find the library
roots, so invoking it directly costs nothing extra and avoids two weaknesses of the URL path: it
does not depend on the `steam://` handler being registered and intact, and it does not route
through a shell that would have to be trusted to parse an argument correctly.

`-applaunch` also takes a bare appid, where `rungameid` accepts the broader identifier space that
includes shortcuts. Choosing the narrower entry point makes the manifest gate harder to circumvent
rather than merely policy.

The invocation carries no additional arguments. Launch options are a host-side Steam setting and
stay there.

### 3. Two stages, because they answer different questions

```
   tap Play
      │
      ▼
   ACCEPTED   agent: "Steam took the command"      ~immediate
      │       app shows a pending state
      │
      ▼
   CONFIRMED  presence: "the desktop is in appid"  within the poll window
              app shows the game as running
```

Acceptance proves the command reached a live agent that passed the gate and invoked Steam. It does
not prove a game started — Steam may be logged out, a game may show a launcher, an anti-cheat
service may refuse, an update may begin instead.

Confirmation comes from presence, which the app already observes. It is a strictly stronger claim
than any self-report the agent could make, because it is measured from outside the machine that was
asked to do the thing. The agent says what it did; presence says what is true.

Immediate acceptance exists purely so the UI is responsive. Without it, a tap would sit inert for
up to a poll interval, which reads as a broken button.

### 4. Failure to confirm is reported as uncertainty, not failure

The confirmation window is generous — games take time to start, and shader compilation, launchers,
and update checks all land inside it. When it expires without presence showing the expected app,
the app says it could not confirm the game started. It does not say the launch failed.

That is not hedging. By then the launch genuinely may have succeeded, and the app has no way to
distinguish a slow start from a silent refusal. Claiming failure for something that is at that
moment loading would be the app's first outright false statement about the world.

### 5. Confirmation depends on internet, and that case is called out

Presence is resolved through the Steam Web API. A phone on the same Wi-Fi as the desktop but with
no internet can reach the agent and launch successfully, and can never confirm.

This is a real and reachable state, not a theoretical one, so it is distinguished explicitly: with
no path to presence, the app reports that it cannot confirm *because it cannot reach Steam*, rather
than letting a working launch decay into an ambiguous timeout. The two conditions have different
causes and different fixes, and conflating them would make the feature feel unreliable exactly when
it worked.

### 6. One launch in flight, and no retry

While a launch is pending the action is unavailable for that game. Steam treats a second
`-applaunch` for a running game as a focus request, so a double tap is harmless on the host — but a
button that stays live through a pending state invites the player to conclude nothing happened.

There is no automatic retry and no queue-until-awake. A launch the player did not just ask for,
arriving whenever the desktop happens to wake, is a surprising thing for software to do with
someone's computer. If the desktop is unreachable the action is unavailable and says so.

### 7. The agent logs every launch locally

Every accepted and refused launch is written to a local log on the host, with the appid and the
outcome. It costs almost nothing and it is the only record that would exist if the channel were
ever misused. An agent that can act on a machine should be able to say what it did.

## Risks / Trade-offs

- **Steam may be logged out, and the launch silently does nothing.** → Surfaces as a confirmation
  timeout. Detecting a logged-out Steam reliably would mean inspecting Steam's internal state,
  which is undocumented and version-fragile; the timeout already communicates the outcome the
  player needs, and the desktop tells them the rest.

- **A game may start into a launcher, a EULA, or an update rather than gameplay.** → Presence
  reports the app as running in the launcher case, so confirmation is honest about what it
  measured: the game is running, not that it is playable. This matches the claim the app actually
  makes.

- **The manifest gate depends on an undocumented file format Valve has changed before.** →
  Inherited from `add-desktop-agent`, where a parse failure must read as "no report," never as an
  empty library. Here the consequence of a parse failure is a refused launch, which is the safe
  direction: a format change makes the feature stop working rather than start launching things it
  should not.

- **Adding an acting verb weakens the agent's simplest safety property.** → Narrowed rather than
  abandoned, and stated that way in the spec: the agent acts only through Steam, and only on apps
  Steam reports installed. Anything beyond that would be a new decision with its own review, not an
  extension of this one.

- **Awake-only will occasionally disappoint.** The desktop sleeps, the button is gone, and the
  player walks over anyway. → Accepted. Wake-on-LAN works only from the same broadcast domain,
  depends on BIOS, NIC power management, and fast-startup settings, and fails in ways that are
  invisible from the phone. A feature that works when it says it works beats one that sometimes
  wakes the machine.
