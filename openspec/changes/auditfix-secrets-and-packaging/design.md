# Design

## Context

Five audit findings, one theme: data crossing a boundary by default rather than by
decision. The fixes are small; the decisions about *which* default to choose are the
substance of this document.

## Decision 1: Auto Backup — explicit policy, not `allowBackup="false"`

**Chosen**: keep `allowBackup="true"`, write real rules that exclude the database,
DataStore, and snapshots.

**Rejected**: `allowBackup="false"`.

Turning backup off entirely is the smaller diff and the easier argument, but it is the
wrong default for this app. Backlogium's data is *irreplaceable*: Steam exposes no
historical presence, and a user who loses their device loses their playtime history
permanently unless they happened to export first. The same reasoning that forbids a
Firestore TTL applies here.

The problem is not that platform backup exists. The problem is that platform backup is
currently including data through a channel the user cannot see, cannot list, cannot
restore selectively, and did not ask for — while the app simultaneously presents an
explicit backup model in settings that the user *can* see and control. Two backup
systems with different retention and different UX, one of them invisible.

So: the explicit model stays authoritative. Room, DataStore, and snapshots are
excluded from Auto Backup. What remains eligible is inert UI state, which is what Auto
Backup is actually good at.

**Consequence to accept**: after this change, a device-to-device transfer no longer
carries the library. The user must export and import. That is a real regression in
convenience, and it is the correct trade — a silent partial restore that mixes
platform-backed Room state against an app-managed snapshot lineage is precisely the
"logically hybrid backup" failure mode the audit raised elsewhere. Better to have one
restore path that works than two that can disagree.

This decision should be revisited if and when the explicit export model gains
automatic off-device sync. At that point Auto Backup becomes redundant rather than
merely dangerous.

## Decision 2: Snapshot relocation and the orphaned-history problem

`noBackupFilesDir` is the correct home: it is app-private *and* excluded from both
cloud backup and device transfer, which is exactly the semantics the snapshot lineage
needs, without depending on XML rules staying correct.

Moving the directory is trivial. Not stranding existing snapshots is the actual work.

```
  first launch after upgrade
        │
        ├── filesDir/<DIR_NAME>/ exists?
        │        │
        │        ├── no  → nothing to do
        │        │
        │        └── yes → move each *.json to noBackupFilesDir/<DIR_NAME>/
        │                   ├── success → delete the old directory
        │                   └── failure → leave both; log; retry next launch
        │
        └── SnapshotStore reads only noBackupFilesDir from here on
```

The relocation must be idempotent and must never delete a source file it did not
successfully copy. A half-moved directory on the next launch should converge, not
compound. Retention pruning runs against the new directory only — pruning during a
partial move could discard the sole surviving copy of a snapshot.

**Rejected**: reading from both directories indefinitely as a compatibility shim. It
would leave the leak in place for any user who never triggers a new snapshot write, and
"eligible for platform backup" is a property of the file's location, not of whether the
app still reads it.

## Decision 3: Redaction — normalize the endpoint, don't extend the denylist

Adding `"steamid"` to `secretParameters` fixes the reported instance and leaves the
mechanism intact. The mechanism is the defect: a denylist of secret parameter names
must be updated in lockstep with an API surface owned by someone else, and it fails
*silently* — an unredacted value looks exactly like a correctly-redacted request until
someone reads the diagnostics screen closely.

The existing `app-diagnostics` requirement says redaction must be applied at the
formatting layer "so that no call site can emit an unredacted value by omission." A
denylist honours the letter and misses the intent: no *call site* can bypass it, but
the formatting layer itself omits by default.

**Chosen**: invert to an allowlist. Store a normalized identifier — the endpoint path,
plus only those query parameters known to be safe and useful for debugging (`appid`,
`count`, `l`, `format`). Anything unrecognized is dropped or rendered as
`<param>=[redacted]`. A new Steam parameter is then invisible to diagnostics by
default, which is a legible failure rather than a silent leak.

**Consequence**: the diagnostics screen loses some incidental detail. Acceptable —
`app-diagnostics` already requires only that "the endpoint and any non-credential
parameters remain legible", and a normalized endpoint name is *more* legible for
grouping and counting than a raw URL.

**Note for whoever implements this**: the existing diagnostic rows in the database were
written under the old scheme and some of them contain a real SteamID. Fixing the
formatter does not clean history. Retention is already bounded, so they age out — but
if that bound is long, a one-shot purge of stored request identifiers belongs in this
change. Verify the retention window before deciding.

## Decision 4: Credential fields — debug-only via variant configuration

Move `buildConfigField` for `STEAM_API_KEY` / `STEAM_ID` out of `defaultConfig` and
into the `debug` variant, with the `release` variant pinned to empty string literals
explicitly rather than by omission. `BuildConfig.STEAM_API_KEY` must still *compile* in
release — the seed path reads it unconditionally — so the field has to exist with an
empty value, not vanish.

Pinning release explicitly, rather than relying on `defaultConfig` being absent, is the
point: it makes "release has no credentials" a statement in the build file that a
reviewer can see, instead of an emergent property of where a line happens to sit.

**Rejected**: failing the release build when `local.properties` contains a key. Too
hostile — a developer with a populated `local.properties` doing a local release build
to check something is a normal act, and it should produce a credential-free APK, not an
error.

## Decision 5: Version metadata — CI-injected, single source of truth in the tag

The tag is already the release trigger and already validated by `release.yml` (reachable
from `master`, matches `vX.Y.Z`). It should therefore be the only place a version is
written. Committing version numbers into `app/build.gradle.kts` creates a second source
that can disagree with the tag, and disagreement between them is exactly the bug.

```
  git tag v1.4.2
        │
        ▼
  release.yml validates (on master? semver?)
        │
        ├─ versionName ← "1.4.2"           (strip the leading v)
        └─ versionCode ← 1*10000 + 4*100 + 2 = 10402
                            │
                            ▼
        ./gradlew assembleRelease -PversionName=... -PversionCode=...
                            │
                            ▼
  build.gradle.kts: project property if present, else a local dev default
```

`major*10000 + minor*100 + patch` is monotonic for the whole plausible life of this
project and reads directly as the version, which matters when the only place you can
see it is a Play console error message. It caps minor and patch at 99 — an acceptable
ceiling, and one worth writing down rather than discovering.

Local builds with no properties supplied fall back to `versionCode = 1` /
`versionName = "0.0.0-dev"`. A dev build should be obviously a dev build.

**Rejected**: deriving `versionCode` from commit count or a timestamp. Both are
monotonic and neither is legible; a build you cannot map back to a tag by reading its
version is a build you cannot support.

**Rejected**: having `bump-tag.{sh,ps1}` edit and commit `build.gradle.kts`. That
reintroduces the second source of truth this decision exists to remove, and adds a
commit-after-tag ordering problem.

## What this change deliberately does not do

- Does not reconsider whether `BuildConfig` should seed credentials at all. That is
  `onboarding-credentials`' question.
- Does not add signing-key handling changes. Out of scope.
- Does not encrypt snapshots at rest. `noBackupFilesDir` addresses the *escape* path;
  at-rest encryption of app-private storage is a separate threat model with a real
  cost (key management, restore across reinstall) and no finding driving it.
