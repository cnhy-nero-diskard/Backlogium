# Phone verification

Verified on 2026-10-03 using the connected 2406APNFAG phone, Android 16 (API 36),
1220 × 2712 pixels at 520 dpi. The current branch's debug app was built, installed
as an update, and launched successfully. Tests used local Library presentation
data and the production `AppBottomNavigation` with a real Compose navigation graph.

## Automated checks

Both `LibraryInsetCaptureTest` and `HomeFirstRunNavigationTest` passed in gesture
navigation and three-button navigation: four tests per mode, eight passes total.

- Exact five-route visibility and selected-tab checks, plus all existing pushed
  routes, Settings graph routes, and an unknown future pushed route.
- List, grid, and compact grid in light and dark themes at 100% and 130% text.
- Library viewport bottom equals the app bar's top in every captured state.
- Final game cards are visible, selectable, and remain accessible after system
  Back from a pushed game-detail route. Library is the selected restored tab.
- Pushed routes reserve only the system navigation inset, with no retained bar
  height. Returning restores the visible bar's measured padding.
- Initial unconfigured Home and interrupted first-run setup open a pushed
  onboarding route. System Back exposes Home's setup guidance, Continue setup
  reopens onboarding, and Library remains reachable before configuration.

| Navigation mode | Text size | System bottom inset | Scaffold bottom padding | Viewport bottom / bar top |
| --- | --- | ---: | ---: | ---: |
| Gesture | 100% | 52 px | 312 px | 738.46 dp |
| Gesture | 130% | 52 px | 318 px | 736.62 dp |
| Three-button | 100% | 153 px | 413 px | 707.38 dp |
| Three-button | 130% | 153 px | 419 px | 705.54 dp |

All three densities and both themes share each row's inset measurements. Raw
measurements and 12 screenshots per mode are in `gesture/library-insets/` and
`three-button/library-insets/`. Representative captures were inspected for safe
spacing and complete final cards. At 130% text, Analytics and Settings labels
wrap; the tabs remain accessible and clear of the system navigation area.
End-of-list spacing is the intentional scrollable content gutter; there is no
fixed strip outside the Library viewport.

## Full app review

The installed app was also exercised with the existing account. Library content
reaches the bar during scrolling. HLTB review, game detail, Settings detail,
credential onboarding, and setup hide the bar. System Back from HLTB review and
game detail restores Library with its tab selected and correct viewport spacing.
Home, Library, and Settings overview show all five tabs. Full-app captures were
reviewed locally under `%TEMP%/backlogium-insets/phone-*.png`; these contain account
presentation data. The committed captures use fixture game names.

First-run entry and interrupted setup were tested with fixture Home states;
the actual credential and setup screens were additionally reviewed through
Settings. Device font and theme preferences were preserved. The temporary
three-button setting was restored to gesture navigation (`navigation_mode=2`,
`force_fsg_nav_bar=1`), and the phone was left on Library.

Build checks: `:app:installDebug` and `:app:assembleDebugAndroidTest` succeeded.
The test APK was installed with `adb install -r`, then the two classes were run
with `adb shell am instrument -w -e class` and `AndroidJUnitRunner`.
Strict OpenSpec validation passed.
