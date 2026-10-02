# Baseline inset capture

Captured on the Medium Phone API 35 emulator (1080 × 2400, 420 dpi) using
`LibraryInsetCaptureTest`. The test composes the current `LibraryContent` with
18 local fixture games inside the shell's `Scaffold`/`NavigationBar` arrangement.
It uses the same `AnimatedVisibility` transition as `BacklogiumAppRoot`. This
avoids changing credentials or game data. The installed release app was not
used as baseline because its Library toolbar differs from this checkout.

| System navigation | Density | System bottom inset | Scaffold bottom padding | Visible bar top |
| --- | --- | ---: | ---: | ---: |
| Gesture | List, grid, compact grid | 63 px (24 dp) | 273 px (104 dp) | 810.3 dp |
| Three-button | List, grid, compact grid | 126 px (48 dp) | 336 px (128 dp) | 786.3 dp |

See each mode's `library-insets/{list,grid,compact_grid}.png` and
`library-insets/measurements.txt`. The final card is fully reachable in all six
captures. Each shows an approximately 16 dp blank band between the final card
and app bar. `LibraryContent` applies `Modifier.padding(16.dp)` outside its
`LazyColumn`, accounting for the fixed band. The shell's bottom padding is
exactly the navigation bar height (80 dp) plus the system bottom inset, so it
does not duplicate the inset while the bar is visible.

At 150 ms into the bar's slide-out, `Scaffold` still reports the full 104/128 dp
bottom padding. Once the exit finishes it reports only the 24/48 dp system
inset. The sliding bar therefore leaves stale content space during the
transition; `AnimatedVisibility` remains measured until its exit completes.
