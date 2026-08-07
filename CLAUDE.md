# Backlogium — agent orientation

Offline-first Android companion for a Steam library: playtime, achievements,
rarity, completion estimates, quests, and streaks as one local progression system.

`README.md` describes *what the project is*. This file covers *how to work in it*.

## Source of truth for behaviour

**`openspec/specs/` is normative, not the code.** Fourteen capability specs live
there — `steam-sync`, `live-status`, `gamification`, `cloud-presence-poller`, and
others. When behaviour is in question, read the spec before inferring intent from
an implementation.

```
openspec/specs/<capability>/spec.md      current agreed behaviour
openspec/changes/<name>/                 in-flight work (proposal, design, specs, tasks)
openspec/changes/archive/<date>-<name>/  completed work, with the reasoning that produced it
```

Archived `design.md` files are the decision record: alternatives considered and
rejected, and why. They are frequently the fastest way to understand *why* something
is the way it is — check them before assuming a design is accidental.

### Workflow

Changes are proposed, applied, and archived through the `opsx` skills
(`/opsx:propose`, `/opsx:apply`, `/opsx:archive`, `/opsx:explore`). Do not edit
`openspec/specs/` directly as part of implementation work — behaviour changes go
through a change with a delta spec, which is then synced on archive.

## Two build systems, one repo

| Path | Toolchain | Command |
|---|---|---|
| `app/`, `gamification/` | Gradle / Kotlin | `./gradlew assembleDebug` |
| `functions/` | npm / TypeScript, Node 22 | `npm --prefix functions run build` |

They are fully independent. `functions/` is invisible to Gradle; a Gradle build
neither needs nor touches it. Do not add one to the other's build graph.

```bash
./gradlew assembleDebug                              # Android debug APK
./gradlew :gamification:test :app:testDebugUnitTest  # unit tests
npm --prefix functions run build                     # typecheck + compile functions
firebase deploy --only functions,firestore:rules     # deploy cloud side
```

## Invariants worth not breaking

**Repositories expose domain models.** Room entities stay inside `data/`. Nothing
under `ui/` imports a storage type — no `data.local.entity.*`, no `SettingsDataStore`
in a ViewModel. Verifiable:

```bash
grep -rn "data.local.entity\|SettingsDataStore" app/src/main/java/com/example/backlogium/ui/
```

One deliberate exception: `HltbCandidate` (`data.hltb`) crosses the boundary as a
plain serializable class, because it is exactly the shape the review surface needs.

**The on-device engine is the sole author of derived values.** Sessions, playtime,
XP, streaks, and levels are computed on the phone, in `:gamification`. The cloud
poller records raw observations and derives nothing. Two independent session
detectors would produce records with disagreeing boundaries that cannot be
deduplicated — this is a load-bearing constraint, not a stylistic preference.

**The app must work with no network and no cloud.** Firestore is additive. Nothing
in the app currently reads it, and the app must not come to require it.

## Cloud poller specifics

A scheduled Cloud Function samples Steam presence every minute and appends game
transitions to Firestore. See `functions/README.md` for operations and
`openspec/specs/cloud-presence-poller/spec.md` for required behaviour.

Constraints that are expensive or impossible to reverse:

- **Firestore lives in `asia-southeast1` and that location is permanent.** The
  function must be deployed to the same region.
- **Retention is indefinite by design. Do not add a TTL policy.** Steam exposes no
  historical presence, so a deleted document is unrecoverable from any source.
- **Writes happen only when `gameid` changes.** Persona state is recorded but never
  compared — Steam cycles idle accounts through away and snooze on its own, which
  previously filled the log with churn and split continuous sessions into fragments.
- **Firestore rules deny all client access.** The poller writes via the Admin SDK,
  which bypasses rules. Any future client reader needs an auth decision first.

## Secrets

- Steam API key on-device: `EncryptedCredentialStore`, Android Keystore-backed,
  seeded from git-ignored `local.properties`.
- Steam API key server-side: Secret Manager (`firebase functions:secrets:set`).
  Never in source, never in function environment config.
- Never commit `local.properties`, `functions/.env`, keystores, or backup files
  containing personal library data.
- The secret is pinned to a version — rotating it requires a redeploy, not just a
  new secret version.

## Conventions

- Match the surrounding code's comment density, naming, and idiom.
- Kotlin for the app and `:gamification`; TypeScript for `functions/`.
- Commit messages explain *why*, not just what. The reasoning behind a change
  should survive in git even when the conversation that produced it does not.
