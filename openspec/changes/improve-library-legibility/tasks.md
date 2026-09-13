## 1. The trophy bar

- [ ] 1.1 Add a trophy progress bar rendering `achievementUnlocked` of `achievementTotal`, beside the existing `AchievementCountLabel` rather than in place of it
- [ ] 1.2 Render it on the Library list row, in the least dense grid cell, and on collection overview member tiles beside `TrophyLabel` — wherever the achievement count already renders, and nowhere else
- [ ] 1.3 Gate it on `density.showsAchievementCount`, so the ladder decides where it appears and no new field is introduced
- [ ] 1.4 Confirm `GameListDensity.visibleFields` is unmodified and the existing `isStrictSubsetOf` assertion still passes without amendment
- [ ] 1.5 Draw nothing — no track, no zero-width fill — when a game has no stored achievement data, keeping missing data distinguishable from zero unlocked
- [ ] 1.6 Draw a zero-progress bar when achievement data is stored and nothing is unlocked, distinguishable from the no-data case
- [ ] 1.7 At 100% unlocked, present the existing `100% Completed` indicator and draw no bar

## 2. Colour and adjacency

- [ ] 2.1 Choose the trophy bar's colour against the palette's documented reservations: not `Gold` (milestone), not `GoldOverrun` (the bar directly above), not `PlayingIndicator` (live), not `SteelBlue` (now-playing lane and `RARE`). `RarityEpic` violet is the leading candidate — already achievement-coded, far from gold and green, saturated enough at bar scale
- [ ] 2.2 Add the light-theme counterpart, following the palette's existing pairing convention
- [ ] 2.3 Check the chosen hue against **both** states of the completion bar above it — the plain gold fill and the gold-plus-rust overrun treatment — in an actual grid cell, not in isolation
- [ ] 2.4 Separate the two bars enough that they do not read as one split control at grid width
- [ ] 2.5 Verify the two remain distinguishable without colour discrimination
- [ ] 2.6 Announce each bar with what it measures and its value, so the distinction is not colour-only

## 3. Library counts

- [ ] 3.1 State each Library section's count alongside its `Focus` and `Your games` headings
- [ ] 3.2 Keep the counts present at every display density
- [ ] 3.3 Where a search or filter reduces a section, state both the shown count and the total held rather than only the reduced figure
- [ ] 3.4 Confirm the counts follow membership as games move between the tracked set and the rest

## 4. Library size elsewhere

- [ ] 4.1 State the library's size on Analytics, alongside the figures that describe the library as a whole
- [ ] 4.2 State it in Settings' Data section
- [ ] 4.3 Add no library size to Home, which presents progress content only
- [ ] 4.4 Derive every count from one shared source rather than three independent `.size` call sites, so the surfaces cannot drift
- [ ] 4.5 Frame the figure as the library as the app holds it — Steam's owned-games response includes tools, utilities, and playtests, and an authoritative "games owned" is a claim this change does not make
- [ ] 4.6 Present no size on any surface in its not-configured state
- [ ] 4.7 Write the shown-versus-total disclosure so it still means something once `add-hidden-games` introduces a hidden set

## 5. Non-regression

- [ ] 5.1 Confirm no entity, DAO, migration, DataStore key, or Steam request was added
- [ ] 5.2 Confirm the densest grid still shows neither an achievement count nor a trophy bar
- [ ] 5.3 Confirm the completion bar's existing behaviour — including its overrun rescale and its accessibility description — is unchanged
- [ ] 5.4 Confirm the existing `100% Completed` indicator is unchanged on rows, grid cells, and game detail
- [ ] 5.5 Confirm collection overview aggregate trophy metrics and the completion-goal banner are unchanged
- [ ] 5.6 Verify layout with an active recency badge present: use the least-dense grid fixture with the completion bar, trophy bar, and recency badge, and the Library list-row fixture with trophy progress and recency badge; confirm no overlap, clipping, or density-ladder regression
