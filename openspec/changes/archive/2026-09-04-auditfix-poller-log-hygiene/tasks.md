## 1. Prerequisites

- [x] 1.1 Re-enumerate every `logger.*` and `console.*` call in `functions/src/` and record which fields each one emits, so the redaction authority is built against the full surface rather than the three sites the audit named. Verified by the list covering all call sites `grep -rn "logger\.\|console\." functions/src/` reports
- [x] 1.2 Confirm the audit's clean verdict on the other call sites still holds — `steam.ts:71,78,88` (exception text, HTTP status) and `steam.ts:133` (`communityvisibilitystate`) must carry no identity. Verified by reading each payload

## 2. Redaction authority

- [x] 2.1 Add a single component in `functions/src/` that log payloads pass through and that owns the rule from the spec's "Operational logs carry no account or title identity" (design.md Decision 1). Verified by unit tests asserting a Steam ID, an app ID, and a game name are each refused or stripped
- [x] 2.2 Give it a shape that makes the safe thing the easy thing — a caller should not be able to emit an identity field by forgetting to redact it. Verified by a test that a payload carrying an identity field does not reach the log unmodified
- [x] 2.3 Document in the component itself why the rule exists (Firestore is access-controlled, logs are not) so the next reader does not treat it as ceremony. Verified by the comment naming the boundary difference

## 3. Call sites

- [x] 3.1 `index.ts:71` — drop `gameid` from the heartbeat, keep `outcome` (design.md Decision 4). Verified by a test asserting the heartbeat is still emitted on a successful poll and contains no app ID
- [x] 3.2 Confirm the heartbeat still satisfies its spec obligations after the edit: emitted on every successful poll including when nothing was written, suppressed on a failed fetch. Verified by the existing heartbeat tests still passing
- [x] 3.3 `presence.ts:165-170` — drop `steamId`, `gameid`, and `gameName`; keep `first` and the outcome. Verified by a test asserting a recorded transition emits an entry with none of the three
- [x] 3.4 `steam.ts:109-112` — drop `steamId` from the unknown-player error while keeping the message that names the setting to check. Verified by a test asserting the entry still identifies the condition and omits the ID
- [x] 3.5 Route the remaining clean call sites through the authority too, so the rule is uniform and a later edit to one of them cannot bypass it. Verified by no direct `logger.*` call remaining outside the authority

## 4. Verification and deploy

- [x] 4.1 `npm --prefix functions run build` passes (typecheck + compile)
- [x] 4.2 `npm --prefix functions test` passes, including the new redaction tests
- [x] 4.3 Add the boundary check to `functions/README.md` as a runnable grep that must produce no output — the pattern `CLAUDE.md` already uses for the haptics authority. Verified by running it on the changed tree and getting silence
- [x] 4.4 `firebase deploy --only functions`. **Region `asia-southeast1`, schedule, secret binding, and retention must be unchanged** — if the deploy proposes altering any of them, stop and investigate. Verified by the deploy summary showing only the function's code revision changing
- [x] 4.5 Read Cloud Logging after the first post-deploy poll and confirm the heartbeat carries `outcome` and no `gameid`. Verified by inspecting an actual emitted entry, not by reading the source
- [x] 4.6 Confirm no TTL policy was introduced — `cloud-presence-poller/spec.md:226` and `CLAUDE.md` forbid one. Verified by the Firestore configuration showing no TTL on the presence subcollection

## 5. Maintainer decisions (explicit answer required, no default)

- [x] 5.1 Decide what to do about Cloud Logging entries **already written**, which this change does not touch: review current retention and any configured sinks or exports, then either delete the historical sensitive entries where operationally possible or record the decision to keep them. Verified by the retention/sink review being written down with the choice made
- [x] 5.2 Answer the git-history attribution finding by picking one of design.md Decision 3's three options — intended-and-accepted, forward-only, or remediate — and record the answer in `tools/hltb-dataset/README.md`. **Do not delete dataset rows or rewrite history unless option 3 is explicitly chosen.** Verified by the README stating the provenance norm for future contributions
- [x] 5.3 If option 1 or 2 is chosen, close #118's second finding as accepted with the reasoning recorded, rather than leaving it open indefinitely as an implied to-do

## 6. Close out

- [x] 6.1 `openspec validate --strict auditfix-poller-log-hygiene` passes
- [x] 6.2 Sync the delta into `openspec/specs/` via the archive workflow, not by hand
- [x] 6.3 Close #118 with the code fix, the deploy confirmation from task 4.5, and the two decisions from section 5 recorded
