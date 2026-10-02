# 1c daily game breakdown acceptance

Validated 2026-10-03 on `feat/activity-clarity-and-trends`.

- Eight daily domain/Room/repository tests and four existing Home day-field tests passed.
- Four emulator instrumentation tests passed: light expansion, dark expansion at 150% font scale, date/account reset and named navigation, and Home detail-return state restoration.
- Captures under `files/` render the production `HomeContent` with deterministic local fixture data on Medium Phone API 35, Android 15, `emulator-5554`. The fixture date is October 3, 2026; fixture recorded/credited amounts are 40/60 minutes. No live account data or artwork is included.
- Inspected all four PNGs: collapsed cards stay compact; expanded scopes and the signed difference are visible; long game names/actions wrap; the large-font breakdown scrolls to its remaining content. The named detail action has a minimum 48 dp tested height. The expansion control exposes localized expanded/collapsed semantics.
- Repository tests preserve sessions and progress during viewing and publish only committed session/credit scopes during correction, with the prior coherent snapshot marked updating. The read repository has no network, scheduler, XP, or progress-write dependency.
- Strict OpenSpec validation and whitespace checks passed. Compile and lint passed; lint retains the repository's existing baseline and warnings.

Device captures use the isolated validation package `com.example.backlogium.activityclarity`, configured only through an ignored local Gradle init script. The existing debug package's schema-44 database was retained and its matching parent-checkout APK restored after the first test launch exposed a schema-42 downgrade mismatch. No database was cleared and no downgrade migration was added.

Scope coordination: [draft #176](https://github.com/cnhy-nero-diskard/Backlogium/pull/176) owns broader provenance classification; these labels describe recorded evidence and earned credit without blanket Steam-source claims. [Draft #177](https://github.com/cnhy-nero-diskard/Backlogium/pull/177) owns unlock-only History and daily XP. This disclosure stays inside the quest card and adds neither feature. `DailyActivity` and its bounded repository remain reusable by 2c.

Unavailable contexts: physical-device and manual TalkBack traversal were not performed. Captures cover production Home content in the test activity, rather than the complete authenticated navigation shell. Navigation return uses the same saveable-state mechanism as the shell; detail eligibility is checked against current local visibility, account, and date before opening.
