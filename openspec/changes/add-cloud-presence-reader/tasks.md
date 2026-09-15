## 1. Read endpoint

- [x] 1.1 Add a bearer credential to Secret Manager and wire it into the functions deployment
      alongside `STEAM_API_KEY`. Verify the secret is referenced by the function and appears in no
      source file or environment config.
- [x] 1.2 Add the read function beside `pollPresence`, in `asia-southeast1`, with `maxInstances`
      bounded. Verify `npm --prefix functions run build` typechecks and the function is registered.
- [x] 1.3 Reject absent, malformed and non-matching credentials before any Firestore read, and
      reject every request when the server credential is absent. Verify by unit test that a
      rejected request performs zero datastore reads and that a missing secret rejects rather than
      admits.
- [x] 1.4 Serve a windowed slice: accept a position, return transitions after it, the current
      state, the account the poller is configured for, and a position to continue from, carrying
      each transition's coverage record — `prevLastObservedAt` and, where present,
      `prevCoverageLapseFrom` / `prevCoverageLapseRecoveredAt` — verbatim. Verify against
      `FakeFirestore` that a position bounds the query, that a full response reports whether more
      remain, and that a stored interior-gap pair round-trips without reduction to a verdict.
- [x] 1.5 Route every log call through `safeLog`. Verify the README boundary grep stays silent:
      `grep -rnE "firebase-functions/logger|console\." functions/src/ --exclude=safeLog.ts --exclude="*.test.ts"`
- [x] 1.6 Confirm `firestore.rules` is unchanged and still denies all client access. Verify no rules
      deploy is part of this change.

## 2. Interval reconstruction

- [x] 2.1 Add the pure reconstruction in `app/domain/`, taking transitions plus current state and
      returning ordered intervals. Verify it has no Room, Android, network or clock dependency, by
      unit tests that construct inputs directly.
- [x] 2.2 Produce an interval per adjacent transition pair, attributed to the earlier game. Verify
      by unit test, including a direct game-to-game switch with no intervening not-playing entry.
- [x] 2.3 Produce the final interval as ongoing when the current state still reports the last
      transition's game, bounded by the latest successful observation. Verify by unit test that no
      end is fabricated.
- [x] 2.4 Carry coverage onto each interval — continuous, observed-until, or unknown — from the
      transition that closed it, and preserve the raw interior-gap pair (`prevCoverageLapseFrom` /
      `prevCoverageLapseRecoveredAt`) verbatim alongside that tail value whenever the transition
      carries one. Verify by unit test for each of the three tail values, including a transition
      carrying no coverage record, which must yield unknown and never continuous, and verify the
      pair is not dropped, normalised to a duration, or folded into the three-state value.
- [x] 2.5 Verify by unit test over a fresh-tail-with-interior-gap fixture (`A@t0 → A@t1 → outage
      → A@t10 → B@t11`, closing as a v3 transition carrying `prevLastObservedAt=t10` with
      `prevCoverageLapseFrom=t1` / `prevCoverageLapseRecoveredAt=t10`) that the reconstructed
      interval is not marked continuous and still carries the `t1..t10` pair for its consumers.
- [x] 2.6 Propagate uncertainty forward: an interval closed by a transition with an earlier
      last-confirmed time marks the following interval as possibly having begun earlier. Verify by
      unit test over an adjacent pair.
- [x] 2.7 Verify by unit test that the reconstruction produces no session, playtime, experience,
      streak or daily progress value.

## 3. Transport and credentials

- [x] 3.1 Add the Retrofit client and response models in `app/data/`. Verify no new Gradle
      dependency was added and that `./gradlew :app:assembleDebug` succeeds.
- [x] 3.2 Store the endpoint URL and credential in the existing Keystore-backed encrypted store,
      masked on display and never logged. Verify by test that the persisted representation is not
      readable plaintext and that no log entry contains the credential.
- [x] 3.3 Verify configuration against the endpoint before storing it, and refuse to store values
      that fail. Verify by test that a failed verification leaves nothing stored.
- [x] 3.4 Assert the returned account against the configured Steam account, discarding the whole
      response on mismatch. Verify by test that no part of a mismatched response reaches a store or
      a surface.
- [x] 3.5 Persist the read position, bound the first read to a recent window, and clear the position
      on a configured account change. Verify by test that a repeated read has no additional stored
      effect and that an account change discards the position.
- [x] 3.6 Expose the reconstruction through a repository returning domain models. Verify the
      boundary grep in `CLAUDE.md` reports no new `data.local.entity` or DAO import under `ui/`.

## 4. Settings surface

- [x] 4.1 Add the cloud presence section: configure, verify, masked credential, last successful
      read, remove. Verify the section is absent from an unconfigured app except for its
      configuration affordance, and presents no error state.
- [x] 4.2 State the account-mismatch cause distinctly from a rejected credential and from an
      unreachable endpoint. Verify each of the three produces its own message.
- [x] 4.3 Show a failing-reads state distinctly from a healthy one, including when the last
      successful read occurred. Verify by test over both states.
- [x] 4.4 Destroy the stored credential on removal and return to the unconfigured state. Verify
      nothing remains readable in the store afterwards.

## 5. Diagnostics surface

- [x] 5.1 Record each read outcome — success with window and count, or a cause distinguishing
      unreachable, rejected credential, account mismatch and unusable response — with its trigger,
      under the existing bounded retention. Verify each cause is recorded distinctly.
- [x] 5.2 Present the reconstructed cloud timeline against the locally recorded sessions for the
      same window. Verify the surface renders both for a window covered by a read.
- [x] 5.3 State coverage per interval, with unknown visually distinct from continuous, and state
      the interior-gap span where the interval carries one. Verify an interval from a pre-phase-1
      transition is not presented as confidently as one carrying coverage, and that a fresh tail
      with an interior gap is not presented as continuous.
- [x] 5.4 Show date-level attribution disagreement, where the cloud places play on a different date
      than the local ledger credits. Verify with a fixture reproducing the phone-was-off case.
- [x] 5.5 Verify the surface offers no action to apply, import or reconcile anything it presents.
- [x] 5.6 Resolve hidden games through the same point live presence uses, so no cloud-derived
      surface names or depicts one. Verify by test that a hidden game is absent from both the
      timeline and the comparison, and returns on unhide without a re-read.

## 6. Read-only guarantee

- [x] 6.1 Verify by test that a completed read leaves the session ledger, daily progress and all
      derived gamification values unchanged, and produces no progress event.
- [x] 6.2 Verify by test that an unconfigured app makes no cloud request on any path, including
      during a sync, and that an unreachable endpoint does not fail, delay or alter a sync.
- [x] 6.3 Run `./gradlew :gamification:test :app:testDebugUnitTest` and
      `npm --prefix functions test` and confirm both suites pass.

## 7. Deploy and verify

- [x] 7.1 Deploy the endpoint before shipping the app. Verify it is in `asia-southeast1`, rejects an
      unauthenticated request, and that no `firestore.rules` deploy was included.
- [x] 7.2 Configure the app against the live endpoint and confirm a read succeeds, the account
      assertion passes, and the position advances on a second read.
- [x] 7.3 Review the diagnostics comparison against a real window where the phone was off, and
      record what the disagreement actually looks like — this is the evidence phase 4's design
      depends on.
