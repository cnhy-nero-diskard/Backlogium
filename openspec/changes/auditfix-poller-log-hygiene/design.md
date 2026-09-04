## Context

See `proposal.md` — Why. Three log sites in the deployed Cloud Function put Steam identity
and played titles into Cloud Logging.

Two properties of the deployment shape the approach:

- **There is no debug/release split.** The Android side keeps identity out of release logs by
  planting Timber only under `BuildConfig.DEBUG`. Functions has no equivalent — anything
  `logger.*` writes ships. So the fix has to be about *what* is logged, not *when*.
- **The poller serves exactly one account.** `STEAM_ID` is a single configured secret value
  (`index.ts:43`), not a per-request parameter. That makes the usual privacy move —
  pseudonymize the identifier — nearly meaningless here, and it is why Decision 2 rejects it.

## Goals / Non-Goals

**Goals:**

- No log entry on any path names the account or the title.
- Faults stay diagnosable: the operator can still tell a timeout from a 403 from a private
  profile, and can still find a misconfigured Steam ID.
- The rule survives future edits — a log line added in six months should not be able to
  re-leak.

**Non-Goals:**

- Anything about *what Firestore stores*. Documents keep the same fields, schema version, and
  transition semantics. The Firestore boundary was not the finding.
- Retention changes. `CLAUDE.md` and `cloud-presence-poller/spec.md:226` forbid a TTL policy
  because Steam exposes no historical presence, and that is not reopened here.
- Remediating log entries already written, or the git-history finding. Both are decisions for
  the maintainer (Decision 3, tasks 5.1 and 5.2).

## Decisions

### Decision 1: One redaction authority, not three edited call sites

**Chosen**: log payloads pass through a single small component in `functions/src/` that
decides what is loggable; the three call sites are edited to use it.

**Alternative considered**: just delete the offending fields at the three sites. Cheaper, and
it fixes today's leak exactly.

**Why the authority wins**: the finding is not really "three bad lines", it is that the rule
was never written down anywhere enforceable. `steam.ts:66` already carries the sharpest
version of it as a comment — `NB: never log url — it carries the API key` — and that comment
successfully protected the key while `steamId` and `gameName` went out three lines below it.
A comment guards the line it sits on. This is the same reasoning `CLAUDE.md` applies to
`ui/util/Haptics.kt` being the sole authority for platform haptic calls, with the same
verification style: a grep that must come back empty.

**Cost**: one more module in a very small codebase, and a call-site indirection for what is
otherwise a one-line logger call. Accepted, because the spec requirement is written as an
obligation on a single component precisely so this cannot regress site-by-site.

### Decision 2: No pseudonymous account token

The audit suggested "only if operationally necessary, a one-way pseudonymous account token."
Rejected.

The function polls one configured account. A stable pseudonym for a population of one is not
a pseudonym — it is a permanent, greppable correlation key for every entry the deployment
will ever write, and anyone who can read the logs can also read `STEAM_ID` from the function
configuration and join the two. It would restore exactly the correlation handle this change
removes while looking like a mitigation.

The operational need it would serve — telling entries apart — does not exist: there is
nothing to disambiguate. If the poller ever serves multiple accounts, this decision should be
revisited then, with the population that actually exists.

### Decision 3: The git-history finding is escalated, not silently remediated

Commits `a843ed4` ("seed the hltb dataset from the maintainer's library") and `ea37d49`
("grow the hltb dataset with a fuller library export") attribute 16 and 272 Steam app IDs
respectively to the maintainer's own library, in a public repository. The current
`tools/hltb-dataset/dataset.json` holds 273 mappings.
`tools/hltb-dataset/README.md` already warns that a contribution export reveals which apps
are owned; the exposure is that the commit messages make the *attribution* recoverable.

This is not a defect with a correct fix — it is a disclosure question only the maintainer can
answer, with three genuinely different answers:

1. **Intended and accepted.** Record that in `tools/hltb-dataset/README.md` so the next
   contributor knows the provenance norm, and close the finding.
2. **Not intended, forward-only.** Future seeds use curated or intentionally-public
   contributions; existing rows and history stay.
3. **Not intended, remediate.** Remove attributable rows from the current dataset, and
   separately decide about history — a rewrite of a public repository is disruptive and
   irreversible in its own way, and would not remove copies others already hold.

No option is applied by default. Task 5.2 requires an explicit answer; the change can land
with the answer recorded and, under option 1 or 2, no dataset edit at all. Deleting rows
without being asked would be the wrong call: it degrades a canonical dataset for a privacy
gain that the maintainer may have already knowingly accepted.

### Decision 4: `outcome` stays in the heartbeat

The heartbeat currently logs `{ outcome, gameid }`. Only `gameid` goes.

`outcome` is `written` vs `unchanged` — it says whether this poll appended a transition. It
carries no title and no identity, and it is genuinely useful: a heartbeat stream that is
permanently `unchanged` distinguishes "pipeline healthy, player idle" from "pipeline healthy,
player active", which is the difference between a quiet week and a stuck `gameid` comparison.
Removing it would cost real diagnostic value for no privacy gain.

## Risks / Trade-offs

**A future log line re-leaks** → Decision 1's single authority, plus a grep-style check in
task 4.3 that must come back empty, modelled on the haptics invariant in `CLAUDE.md`.

**Redaction makes a real incident harder to diagnose** → The fault-shape fields all stay
(status code, exception text, `communityvisibilitystate`). The scenario "Faults remain
diagnosable" exists in the spec to make this a reviewable obligation rather than a hope. If a
future incident genuinely needs identity, the answer is a temporary, deliberate,
time-boxed change — not a standing field.

**The leak continues until deploy** → Unavoidable: redaction lives in the deployed artifact.
Task 4.4 makes the deploy part of the change rather than a follow-up, so the change is not
"done" while the function still leaks.

**Historical entries stay readable after this lands** → Task 5.1 requires a decision on
retention and sinks. This change cannot make that decision, and closing the code fix without
asking would leave the maintainer thinking the disclosure is fully handled when the existing
window of entries is still there.

## Migration Plan

1. Land the redaction authority and the three call-site edits with tests.
2. `npm --prefix functions run build` — typecheck and compile must pass.
3. `firebase deploy --only functions` to `asia-southeast1`. **Region, schedule, secret
   binding, and retention are unchanged**; if a deploy prompts to alter any of them, stop and
   investigate rather than confirming.
4. Confirm from Cloud Logging that new heartbeat entries carry `outcome` and no `gameid`.

Rollback is a redeploy of the previous revision. Nothing on a device and nothing in Firestore
depends on this change, so rollback carries no data risk — it only restores the leak.

## Open Questions

None blocking. Tasks 5.1 and 5.2 carry the two maintainer decisions; neither changes the
specs, the approach, or the task breakdown here, which is why they are tasks rather than
blockers.
