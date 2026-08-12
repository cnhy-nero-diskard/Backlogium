## 1. Agent skeleton

- [ ] 1.1 Create `agent/` with `go.mod`, `cmd/backlogium-agent/main.go`, and a `.gitignore` for build output; confirm Gradle is unaffected by running `./gradlew assembleDebug`
- [ ] 1.2 Add `agent/README.md` covering build, first run, autostart, the Windows Firewall prompt on the Private profile, and the SmartScreen warning for an unsigned binary — firewall first, since it is the most likely cause of "it does not appear"
- [ ] 1.3 Define a `Host` interface for every OS-specific concern (locating Steam, path conventions, autostart, tray) with a Windows implementation, so no OS assumption reaches the protocol layer
- [ ] 1.4 Add a tray icon exposing the agent's status, its address, and quit
- [ ] 1.5 Add autostart via a Startup-folder shortcut, installable and removable from the tray

## 2. Reading Steam's installation records

- [ ] 2.1 Locate the Steam installation on Windows via the registry, behind the `Host` interface
- [ ] 2.2 Parse `libraryfolders.vdf` defensively to enumerate every configured library root
- [ ] 2.3 Parse `appmanifest_<appid>.acf` in each root for appid and size on disk
- [ ] 2.4 Distinguish three outcomes explicitly — a successful report, a genuinely empty library, and a failure to read or parse — and never let a failure render as an empty set
- [ ] 2.5 Add Go unit tests over recorded fixture files, including a malformed manifest and an unrecognised `libraryfolders.vdf` shape

## 3. Trust and transport

- [ ] 3.1 Generate a self-signed certificate on first run and persist it beside the agent's state
- [ ] 3.2 Implement the pairing endpoint: a code displayed in the tray, a bounded window, rate-limited attempts, single-use, yielding a shared secret
- [ ] 3.3 Require an HMAC over payload, nonce, and timestamp on every non-pairing request; reject stale timestamps and replayed nonces
- [ ] 3.4 Implement `GET /v1/hello` (identity, machine name, version) and `GET /v1/installed` (the report); expose no other operation
- [ ] 3.5 Add Go tests for refusal paths: unsigned, replayed, stale, expired pairing code, and unknown operation
- [ ] 3.6 Advertise `_backlogium._tcp` over mDNS, including the agent's identity so a discovered agent can be matched to a pairing

## 4. App-side client

- [ ] 4.1 Add `data/agent/` with a discovery source using `NsdManager` and a manual-address path of equal standing
- [ ] 4.2 Implement pairing: submit the code, retain the secret in `EncryptedCredentialStore`, pin the agent's certificate fingerprint
- [ ] 4.3 Implement the signed client for `hello` and `installed`, refusing to proceed when the presented certificate does not match the pinned fingerprint
- [ ] 4.4 Add a Room table for installed state plus its report timestamp, and its migration; treat it as re-fetchable so a schema change drops and rebuilds
- [ ] 4.5 Add a repository exposing installed state as domain models, keeping entities inside `data/`
- [ ] 4.6 Fetch on app foreground and on manual request; never treat an unreachable host as an error state of the app
- [ ] 4.7 Add tests for the client's refusal paths and for retention of dated state across an unreachable fetch

## 5. App surfaces

- [ ] 5.1 Add the paired-desktop section to Settings: discover, enter address, pair, show status, unpair
- [ ] 5.2 Clear the retained installed state and the stored secret on unpair
- [ ] 5.3 Show installed state and its confirmation date on game detail, and nothing at all when unpaired or never reported
- [ ] 5.4 Add the ready-to-play Library filter, absent rather than empty when no report exists
- [ ] 5.5 Verify existing Library sort, grouping, and density behaviour is unchanged with the filter applied

## 6. Documentation

- [ ] 6.1 Add the third row to `CLAUDE.md`'s build table (`agent/`, Go, `go build`) and state that it is independent of both existing build systems
- [ ] 6.2 Record in `CLAUDE.md` that the agent is read-only by design and that a command verb is a separate, later decision
- [ ] 6.3 Note in `agent/README.md` that SteamOS is an open decision, and what would differ — immutable rootfs, persistence across system updates, autostart mechanism

## 7. Verification

- [ ] 7.1 Run `go build ./...` and `go test ./...` in `agent/`
- [ ] 7.2 Run `./gradlew :gamification:test :app:testDebugUnitTest` and `./gradlew assembleDebug`
- [ ] 7.3 Confirm the repository-boundary invariant still passes: `grep -rn "^import .*\(data\.local\.entity\|SettingsDataStore\)" app/src/main/java/com/example/backlogium/ui/ --exclude-dir=diagnostics`
- [ ] 7.4 Confirm no Firebase client dependency was added to `app/`
- [ ] 7.5 Manually verify pairing end to end, then restart both phone and host and confirm no re-pairing is needed
- [ ] 7.6 Manually verify the unreachable path: sleep the desktop, confirm installed state remains visible and dated, and that no failure alerts recur
- [ ] 7.7 Manually verify manual address entry on a network with multicast unavailable
- [ ] 7.8 Manually verify the app is unchanged with no agent paired and with no network at all
