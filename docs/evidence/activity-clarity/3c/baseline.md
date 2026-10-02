# Analytics overview baseline

Captured after 2c commit 837d2b57 and before 3c UI edits. Android 15/API 35 Medium Phone emulator-5554, isolated debug test application, fixture date October 3, 2026. Production AnalyticsContent renders in ScreenshotTestActivity. Light 100% and dark 150%; labelled files in [baseline](baseline/).

AnalyticsScopeDeviceTest passed seven baseline states: leading game, comparison, empty, equal leading totals, missing local artwork, current month and current year. The empty capture was repeated after waiting for the first display frame; the initial blank capture was replaced.

The leading fixture has 100 minutes for the primary game and 80 for another game. The same card shows the game name/100 minutes followed by 180 tracked minutes without visible role or all-games scope. This reproduces the reported visual association ambiguity. At 150% the long title is heavily truncated. The month/year label outside the card names the full calendar identity, without the exact elapsed dates on the overview.

Comparison and empty variants correctly carry no game panel. Tied totals show a consistent selected game; unavailable artwork keeps a themed placeholder. No faulty selection algorithm or incorrect game identity was reproduced. Fixture captures do not cover the authenticated shell, a physical device, or live artwork services.
