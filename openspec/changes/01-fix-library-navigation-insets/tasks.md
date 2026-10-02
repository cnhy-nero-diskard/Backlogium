## 1. Reproduce and Measure

- [x] 1.1 Capture the current Library bottom gap in list and both grid densities under gesture and three-button navigation; verify screenshots and inset measurements identify the visible strip or record the exact conditions under which it does not reproduce.
- [x] 1.2 Trace shell `Scaffold` padding, Library padding, and bar animation against those captures; verify the suspected duplicate or stale space is tied to a measured layer before changing it.

## 2. Navigation and Insets

- [ ] 2.1 Replace route-exception bar visibility with an explicit five-destination allowlist; verify Home, Library, History, Analytics, and Settings overview show the bar while onboarding, setup, HLTB review, diagnostics, collections, gap planning, and other pushed screens hide it.
- [ ] 2.2 Correct bottom inset/padding ownership at the measured layer; verify the Library final item remains fully reachable in list and both grids without a blank strip in gesture and three-button modes.
- [ ] 2.3 Align bar transition and content viewport behavior; verify opening a pushed route and using system Back restores the right tab, padding, and accessible last item.

## 3. Device Review

- [ ] 3.1 Capture representative phone or emulator screens in both themes and at increased font size; verify bar visibility, safe-area spacing, and final-item access across first-run configuration and Library return.
