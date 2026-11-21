# History clarity baseline

Captured before 2c presentation edits, after 1c commit ca81fdab. Android 15 / API 35 Medium Phone emulator-5554, isolated debug application `com.example.backlogium.activityclarity`, UTC fixture dates September 25?October 3, 2026. The production HistoryContent is hosted in ScreenshotTestActivity. No account credentials or network are used.

`HistoryClarityDeviceTest` passed 3 baseline cases with `captureStage=baseline`. Files in [baseline](baseline/) are labelled by fixture, theme, font context, and overnight/progress-only view.

- Multi-game: primary sessions at 09:00 (40 minutes) and 11:00 (20, open), with side game at 10:00 (30). The game ordering is internally correct, but its amount-based grouping is implicit beside the gutter. Stored quest credit 110 is absent from the visible 90-minute total. The 09:00 row has partial recovered / full timing cloud facts.
- Overnight: 23:50 start, 80 recorded minutes, open, attributed once to October 2. Without progress, the baseline incorrectly presents a default failed quest.
- Sparse: 03:00 (5) and 22:00 (10) records; quest credit 5 is absent. Spacing is not proportional, but the gutter does not visibly explain that.
- Progress-only September 25: stored 60 minutes / met quest, no sessions. Baseline says zero minutes played and quest met without identifying credit or missing allocation.
- Dark at 150% font: header title and game names are truncated/wrapped by the competing amount/help columns. Light at 100% is readable.

The original report has no exact screenshot or sample. These fixtures reproduce the scope/grouping ambiguity and the missing-outcome problem; they do not establish a faulty ordering or chronological algorithm. Screenshots are controlled local evidence, not a complete authenticated-shell or physical-device run.
