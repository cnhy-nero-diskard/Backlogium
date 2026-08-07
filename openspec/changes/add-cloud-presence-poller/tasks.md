## 1. Prerequisites (console and account)

- [x] 1.1 Create the Firebase project on the Blaze plan
- [x] 1.2 Create the Firestore database in `asia-southeast1`, Native mode, production-mode rules
- [x] 1.3 Install `firebase-tools` and authenticate with `firebase login`
- [x] 1.4 Store the Steam Web API key as `STEAM_API_KEY` in Secret Manager
- [x] 1.5 Set a billing budget alert (~$5) in Google Cloud Console → Billing → Budgets & alerts
- [x] 1.6 Confirm the Steam profile and game details are set to Public, and record the Steam ID to poll

## 2. Repo scaffolding

- [x] 2.1 Scaffold Functions and Firestore config at the repo root (hand-written rather than via interactive `firebase init`, so the file set is reviewable in the diff)
- [x] 2.2 Add `.firebaserc` naming the Firebase project, and confirm the Firestore region matches `asia-southeast1` — **blocked: needs the project ID**
- [x] 2.3 Add `functions/node_modules` and any build output to `.gitignore`
- [x] 2.4 Verify a Gradle build still succeeds and is unaffected by `functions/`
- [x] 2.5 Add a short `functions/README.md` covering deploy, logs, and secret rotation

## 3. Firestore rules and lifecycle

- [x] 3.1 Write `firestore.rules` denying all client read and write access
- [x] 3.2 Deploy rules and confirm a client-SDK read is refused
- [x] 3.3 Confirm no TTL policy is configured on any collection; retention is indefinite by design

## 4. Steam client

- [x] 4.1 Implement a typed `GetPlayerSummaries` fetch for a single Steam ID
- [x] 4.2 Read the API key from Secret Manager at runtime; never from source or environment config
- [x] 4.3 Map the response to an observation: persona state, game ID, game name, observation time
- [x] 4.4 Treat request failure and malformed responses as "no information", distinct from "offline"
- [x] 4.5 Log distinguishably when the response reports online but omits game attribution

## 5. Presence writer

- [x] 5.1 Read the `players/{steamId}` document and compare the game ID against the new observation
- [x] 5.2 On no material change, perform no write and leave `since` and `updatedAt` untouched
- [x] 5.3 On change, update `current` with the new state and reset `since` to the observation time
- [x] 5.4 On change, append `players/{steamId}/presence/{ISO-8601 timestamp}` keyed by observation time so retries overwrite rather than append
- [x] 5.5 On first run, create `current` with `since` and `updatedAt` equal
- [x] 5.5a Stamp every written document, in both paths, with `v: 1`
- [x] 5.6 On fetch failure, exit without touching either path
- [x] 5.7 Confirm no session, duration, playtime, or experience value is written anywhere

## 6. Schedule and deploy

- [x] 6.1 Define the scheduled function at a one-minute cadence, deployed to `asia-southeast1`
- [x] 6.2 Bind the `STEAM_API_KEY` secret to the function
- [x] 6.3 Deploy, accepting the CLI prompts to enable Cloud Functions, Cloud Build, Cloud Scheduler, Artifact Registry, and Eventarc
- [x] 6.4 Deploy first at a slow cadence, confirm one write lands with the expected shape, then enable the one-minute schedule

## 7. Verification

- [x] 7.1 Start a game and confirm exactly one `presence` document is appended and `current` reflects the game
- [x] 7.2 Let several polls pass unchanged and confirm no new documents and an unmoved `since`
- [x] 7.3 Stop the game and confirm exactly one further `presence` document and a reset `since`
- [ ] 7.4 Simulate a Steam API failure and confirm neither path is modified
- [x] 7.5 Confirm the app's own presence and session behaviour is unchanged, with no Android source modified in this change
- [ ] 7.6 Check invocation count and billing after 24 hours against the expected ~1,440/day

## 8. Exclude persona state from change detection

Amendment made after 24 hours of live data: half the log was idle churn, and an
away/online flap at 05:29–05:30 split a continuous Wuthering Waves session into
three fragments.

- [x] 8.1 Amend the spec so only a game-ID change is material, with persona state still recorded as a field
- [x] 8.2 Record the rationale and the rejected alternative in `design.md`
- [x] 8.3 Narrow `isMaterialChange()` in `presence.ts` to compare game ID only
- [x] 8.4 Redeploy and confirm idling no longer produces entries
- [ ] 8.5 Confirm a mid-session idle leaves `since` unmoved and appends nothing

## 9. Resolve open questions

- [ ] 9.1 Decide whether poller staleness needs active alerting, or whether checking `current.updatedAt` by hand is enough until a consumer exists
