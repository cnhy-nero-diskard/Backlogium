## Context

Installed state is not available from any Steam Web API endpoint. It exists only on the host, as
`steamapps/libraryfolders.vdf` listing the library roots and an `appmanifest_<appid>.acf` per
installed app inside each. Reading it requires code on the machine.

Constraints that shape everything below:

- **Windows first.** SteamOS is explicitly an open decision, not a dropped one.
- **Awake-only.** No Wake-on-LAN. A sleeping host is simply unreachable, and the app says so.
- **Personal use.** No installer, no code signing, no update channel.
- **Same repo.** `settings.gradle.kts` includes only `:app` and `:gamification`, so a top-level
  `agent/` is invisible to Gradle exactly as `functions/` is.
- **Read-only.** The command channel is a later change. Nothing here mutates the host.
- `EncryptedCredentialStore` already exists, Keystore-backed, holding the Steam key.
- The app must continue to work with no network and no cloud.

## Goals / Non-Goals

**Goals:**

- The app can tell the player which owned games are installed on their desktop right now.
- Pairing is a one-time act that survives reboots on both sides.
- A hostile device on the same Wi-Fi cannot impersonate the agent or forge its reports.
- Nothing the agent exposes can change anything on the host.
- Host-specific behaviour is isolated well enough that SteamOS is a later recompile.
- The feature's absence is invisible: an unpaired app is the app as it is today.

**Non-Goals:**

- Launching games. `add-remote-launch` adds the only verb that acts, on top of this foundation.
- Waking, sleeping, or otherwise controlling the host.
- Streaming, input forwarding, or anything resembling remote control.
- Multiple paired hosts. The protocol leaves room; v1 pairs one.
- Cloud relay or reachability from outside the LAN.
- Any use of Firestore.

## Decisions

### 1. Go, accepting a third language in the repo

The agent is a background process on a Windows desktop, and distribution dominates the choice. Go
produces a single static executable of a few megabytes with no runtime dependency, and
`GOOS=linux GOARCH=amd64` produces the SteamOS build from the same source when that decision is
made.

The alternative that preserves repo consistency is TypeScript, reusing `functions/`' toolchain.
Rejected: it requires either a preinstalled Node on the host or a single-executable bundle roughly
ten times the size, which reliably attracts Defender heuristics on an unsigned binary. That is
paying for repo tidiness with the user's install experience, on the machine least able to absorb
it. `.NET` was considered and rejected for making a SteamOS build materially harder — the opposite
of the property being preserved.

`functions/` already established that an independent toolchain can live in a sibling directory
without entering Gradle's build graph. This is the same pattern, not a new one.

### 2. Read-only first, as a security ordering rather than a scoping convenience

Splitting the agent from the launch verb is not only about change size. An agent that answers "what
is installed" has an attack surface consisting of information disclosure about a game library. An
agent that accepts "run this" is a remote execution primitive on a personal machine. Establishing
discovery, pairing, certificate pinning, and request signing against the harmless version means the
dangerous verb arrives on a trust model that has already been reviewed and used, rather than
arriving alongside it.

### 3. Trust-on-first-use TLS, plus signed requests

Android blocks cleartext HTTP by default from API 28, and the escape hatch is a
`networkSecurityConfig` exception that cannot be scoped cleanly to a LAN address discovered at
runtime. Rather than widen cleartext policy app-wide for one feature:

- The agent generates a self-signed certificate on first run and keeps it.
- Pairing pins its fingerprint on the phone. Subsequent connections require that exact certificate.
- Every request additionally carries an HMAC-SHA256 over its payload, a nonce, and a timestamp,
  keyed by the paired secret. The agent rejects a stale timestamp or a replayed nonce.

Neither half alone is sufficient — TOFU alone trusts whoever answers first at pairing time, and
HMAC alone leaves the transport readable. Together they cost little and mean the pairing moment is
the only window of exposure, which is the same trust model SSH has used for decades.

*Alternative considered:* plain HTTP with HMAC only, on the grounds that "which games are installed"
is not a secret. Rejected — it requires relaxing the app's cleartext policy, and the same transport
is about to carry a launch command in the next change. Building the weaker thing now guarantees
rebuilding it later.

### 4. mDNS discovery with manual entry as a peer, not a fallback

Multicast DNS fails on AP isolation, guest VLANs, and several consumer mesh systems. A
discovery-only design would work perfectly in testing and be intermittently broken in the field,
which is the worst failure mode available.

Manual host entry is therefore a supported path with equal standing in the UI, not a hidden
troubleshooting step. Discovery is a convenience over it.

### 5. Installed state is host-owned, cached, and always dated

The app stores the last received report in Room so the Library filter works while the desktop is
asleep — which, given awake-only, is most of the time.

Cached host data can be wrong, and the honest handling is to date it rather than hide it. Every
surface showing installed state also carries when it was last confirmed, and the app never presents
a stale report as current fact. A game uninstalled an hour ago still reads as installed until the
next report; saying "as of this morning" is the difference between a stale cache and a lie.

The table is derived and re-fetchable in full, so schema changes drop and rebuild rather than
migrate.

### 6. The owned view and the local view are separate facts

Steam ownership comes from the Web API; installed state comes from the host. They can disagree
legitimately — a family-shared title is installed but not owned; an owned game is installed on a
different PC.

The app treats them as independent rather than reconciling them. Installed-but-not-owned is simply
not shown, since the app has no library row to attach it to. Owned-but-not-installed is the
common, useful case and is exactly what the ready-to-play filter is built on.

### 7. One host-specific interface, so SteamOS stays open

Everything that differs between Windows and SteamOS — locating the Steam installation, path
conventions, the autostart mechanism, and the tray integration — sits behind a single interface
with a Windows implementation. The wire protocol carries no Windows concepts.

The point is not to build SteamOS support now. It is to ensure the later decision is a package to
write, not a design to redo. SteamOS additionally has an immutable rootfs and update cycles that
remove software outside the home directory, so it will need its own persistence approach
regardless — which is precisely why it is a separate decision.

### 8. No installer, no signing, given personal use

The binary is built with `go build` and started from the Startup folder. SmartScreen's unsigned-
binary warning on first run is accepted and documented in `agent/README.md` rather than paid for
with a signing certificate. Windows Firewall will prompt on first bind and must be allowed on the
Private profile; this is the single most likely cause of "it does not appear," so it is documented
first rather than last.

If distribution to anyone else ever happens, signing and an installer become real work — noted here
so the omission reads as a decision rather than an oversight.

## Risks / Trade-offs

- **A third toolchain raises the repo's onboarding cost.** → Contained the same way `functions/` is:
  independent build, own README, its own row in `CLAUDE.md`'s table, and no entanglement with the
  Gradle graph. An agent change never requires a Gradle build, or the reverse.

- **The agent runs unsigned and prompts on install.** → Accepted for personal use, documented
  prominently. It is the first thing a future distribution decision would have to revisit.

- **Pairing has a trust window.** Anyone who can reach the agent during the PIN window and guess a
  six-digit PIN could pair. → The window is short, opened deliberately by the user at the host, and
  closes on first successful pair or timeout. Failed attempts are rate-limited. For a LAN-only,
  personal-use daemon this is proportionate.

- **Cached installed state can be wrong.** → Every surface dates it, and the ready-to-play filter
  is a convenience rather than a guarantee. The cost of being wrong is a game that needs
  downloading — recoverable, and visible the moment the player looks at the desktop.

- **mDNS on Android needs care.** `NsdManager` has a long history of platform quirks. → Manual entry
  is a peer path precisely so discovery problems degrade to mild inconvenience rather than a
  broken feature.

- **`libraryfolders.vdf` is an undocumented format that Valve has changed before.** → Parse
  defensively and treat a parse failure as "no report," never as "nothing installed." An empty
  report and a failed read must not be confusable, or a parser regression would silently empty the
  player's ready-to-play list.
