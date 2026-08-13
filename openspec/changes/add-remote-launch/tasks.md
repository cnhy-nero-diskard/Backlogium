## 1. Agent launch path

- [ ] 1.1 Add a `Launch(appId)` method to the `Host` interface with a Windows implementation invoking the Steam executable located via the registry with `-applaunch <appid>` and no further arguments
- [ ] 1.2 Reuse the installation-record parsing from `add-desktop-agent` to gate every launch on an `appmanifest_<appid>.acf` existing in a configured library
- [ ] 1.3 Refuse the launch when installation records cannot be read or parsed, rather than permitting it unchecked
- [ ] 1.4 Add `POST /v1/launch`, signed and replay-protected on the same terms as every other operation, returning acceptance or a typed refusal
- [ ] 1.5 Write each accepted and refused launch to a local log on the host, with the appid and outcome
- [ ] 1.6 Add Go tests: an installed appid accepts; an uninstalled one refuses; an identifier with no manifest refuses; an unreadable library refuses; no arguments are ever appended
- [ ] 1.7 Confirm no operation exists to stop, uninstall, update, or change host settings

## 2. App-side launch call

- [ ] 2.1 Add the launch call to the agent client, reusing the pinned certificate and signed-request path
- [ ] 2.2 Model launch state as accepted, confirmed, refused, unconfirmed, or unconfirmable-for-connectivity
- [ ] 2.3 Prevent a second launch for a game while one is pending for it
- [ ] 2.4 Ensure no launch is retried automatically and none is stored for later delivery when the host is unreachable

## 3. Two-stage confirmation

- [ ] 3.1 On acceptance, enter a pending state on `GameDetailViewModel` and start a bounded confirmation window
- [ ] 3.2 Resolve to confirmed when the existing live-status presence path reports the desktop in the launched app
- [ ] 3.3 On window expiry, resolve to could-not-confirm — never to failed — and allow another attempt
- [ ] 3.4 Detect the case where presence cannot be resolved at all for want of internet, and report that distinctly from an expired window
- [ ] 3.5 Honour a late confirmation arriving after the window has expired
- [ ] 3.6 Add tests over the state machine for each resolution, including late confirmation and the connectivity-blocked case

## 4. Game detail surface

- [ ] 4.1 Offer the Play on Desktop action only when paired, reachable, and reported installed
- [ ] 4.2 State the reason plainly when the action is absent — not installed, or desktop unreachable
- [ ] 4.3 Present the pending state, honouring `rememberReducedMotion()`
- [ ] 4.4 Present a confirmed launch consistently with a game started by hand
- [ ] 4.5 Present refusal and could-not-confirm distinctly, both returning the action to its offered state
- [ ] 4.6 Verify game detail is byte-for-byte unchanged in behaviour when no agent is paired

## 5. Documentation

- [ ] 5.1 Update `agent/README.md`: the agent now has exactly one acting operation, what gates it, and where launches are logged
- [ ] 5.2 Update the `CLAUDE.md` note from `add-desktop-agent` to record that the command verb has landed, that it is gated on installation records, and that widening it is a new decision rather than an extension

## 6. Verification

- [ ] 6.1 Run `go build ./...` and `go test ./...` in `agent/`
- [ ] 6.2 Run `./gradlew :gamification:test :app:testDebugUnitTest` and `./gradlew assembleDebug`
- [ ] 6.3 Manually verify a successful launch end to end: tap, pending, game starts, presence confirms
- [ ] 6.4 Manually verify an uninstalled game offers no action, and that a direct request for it is refused by the agent
- [ ] 6.5 Manually verify a non-Steam shortcut's appid is refused
- [ ] 6.6 Manually verify the unconfirmed path by launching with Steam logged out, and confirm the app says it could not confirm rather than that the launch failed
- [ ] 6.7 Manually verify the connectivity-blocked path on a LAN with no internet: launch succeeds, and the app states it cannot reach Steam to confirm
- [ ] 6.8 Manually verify sleeping the desktop removes the action and queues nothing
