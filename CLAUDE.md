# Backlogium — agent orientation

Offline-first Android companion for a Steam library: playtime, achievements,
rarity, completion estimates, quests, and streaks as one local progression system.

`README.md` describes *what the project is*. This file covers *how to work in it*.

## Source of truth for behaviour

**`openspec/specs/` is normative, not the code.** One spec per capability lives
there — `steam-sync`, `live-status`, `gamification`, `cloud-presence-poller`, and
others; `ls openspec/specs/` is the current list. When behaviour is in question,
read the spec before inferring intent from an implementation.

**Specs win over tests and implementation.** If a test disagrees with a normative
spec, treat the spec as authoritative and propose a spec change before changing
behaviour. A test that intentionally encodes known-incorrect behaviour must say so
in a comment and name the change that will make it correct.

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

`tools/` is not a third build system. It holds dependency-free repository scripts, including the
Node 22 HLTB dataset validator/merger; it has no package manifest or install step and belongs to
neither the Gradle graph nor the `functions/` npm graph.

```bash
./gradlew assembleDebug                              # Android debug APK
./gradlew :gamification:test :app:testDebugUnitTest  # unit tests
npm --prefix functions run build                     # typecheck + compile functions
node tools/hltb-dataset/merge.mjs --check tools/hltb-dataset/dataset.json  # dataset gate
firebase deploy --only functions,firestore:rules     # deploy cloud side
```

## Invariants worth not breaking

**Repositories expose domain models.** Room entities stay inside `data/`. Nothing
under `ui/` imports a storage type — no `data.local.entity.*`, no `SettingsDataStore`
in a ViewModel. Verifiable (matching on `import` skips prose mentions in KDoc, and
`--exclude-dir` skips the documented exception below):

```bash
grep -rn "^import .*\(data\.local\.entity\|SettingsDataStore\)" \
  app/src/main/java/com/example/backlogium/ui/ --exclude-dir=diagnostics
```

Two deliberate exceptions:

- `HltbCandidate` (`data.hltb`) crosses the boundary as a plain serializable class,
  because it is exactly the shape the review surface needs.
- **`ui/diagnostics/` reads `DiagnosticsDao` directly** and renders `SyncRun`,
  `RequestBreakdown`, and `PresenceDecision` verbatim. This is a developer-facing
  debug surface whose whole purpose is to show the stored rows as stored — a
  parallel set of identical domain models plus an identity mapper would add a layer
  that can only ever misrepresent the thing being debugged. The exception is scoped
  to this package: it is not licence for product surfaces to reach past a repository.
  Writes still go through `SyncRunRecorder` (`data.diagnostics`), never the UI.

Known outstanding breach, not an exception: `ui/home/HomeViewModel.kt` imports the
`Collection` and `CollectionMember` entities because `CollectionRepository` exposes
them across its whole public API. The fix is to map at the repository boundary; it
is deferred because the collections UI surface is broad. The grep above reports it.

**Haptics have one authority.** `ui/util/Haptics.kt` owns every platform haptic constant and
`performHapticFeedback` call. UI surfaces name an intent from its closed vocabulary; they do not
reach the platform directly. Verify the boundary with:

```bash
grep -rn "performHapticFeedback\|LocalHapticFeedback\|VibrationEffect" app/src/main/java --exclude-dir=util
```

The command must produce no output: `--exclude-dir=util` intentionally omits the authority, so any
match indicates a platform haptic call outside `ui/util/`. Silence is the default: navigation, list
interaction, filtering, sorting, density changes, and newly added controls need no per-site
declaration. Haptics are reserved for the small set of earned or committed moments named by the
shared vocabulary, and never replace the visible result.

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
- **Transition history writes happen only when `gameid` changes.** Every successful
  observation also advances `lastObservedAt` on the current-state document, but it
  does not append a transition. Persona state is recorded but never compared — Steam
  cycles idle accounts through away and snooze on its own, which previously filled
  the log with churn and split continuous sessions into fragments.
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
