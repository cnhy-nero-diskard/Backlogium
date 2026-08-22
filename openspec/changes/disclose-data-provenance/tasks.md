## 1. The vocabulary

- [ ] 1.1 Add a provenance vocabulary in `ui/util/`, beside `UiFormat` — three terms (observed, tracked, inferred) with their player-facing wording, documented with the `SessionDiffer.startAt = previousPollAt` line as the reason the vocabulary exists
- [ ] 1.2 Add a total classification from figure identity to term, so a figure without a term is a compile or test failure rather than an unmarked number
- [ ] 1.3 Classify the existing figures per spec: lifetime playtime, achievement unlock time, global unlock percentage, and current player count as observed; daily minutes, per-day totals, quest totals, and windowed most-played as tracked; session start, session count, average length, longest session, and time-of-day buckets as inferred
- [ ] 1.4 Add a unit test asserting the classification is total over the enumerated figures, so a figure added later cannot ship unclassified

## 2. The shared explanation

- [ ] 2.1 Add one explanation surface stating: sessions are derived by comparing Steam's cumulative playtime between periodic checks; a session's start is the check before the increase appeared; a longer interval moves that start earlier than it occurred
- [ ] 2.2 Do not quote a tolerance. The periodic worker's 15 minutes is a minimum, not a bound — WorkManager defers under Doze, so the error is unbounded above
- [ ] 2.3 Make the surface reachable from any inferred figure's disclosure, and confirm it renders identically whichever screen opened it
- [ ] 2.4 Confirm it is reachable and readable by an accessibility service

## 3. Disclosure rendering

- [ ] 3.1 Add a compact disclosure affordance that sits with a figure, opens the shared explanation, and occupies the same footprint whichever term it carries
- [ ] 3.2 Support disclosing one term for a group of figures that share it, so the session-insights card carries one marker rather than three
- [ ] 3.3 Carry the term in the figure's content description, so provenance is never conveyed by glyph, tint, or typography alone
- [ ] 3.4 Verify the tracked disclosure reads distinctly from the inferred one — the daily chart must not appear as doubtful as the time-of-day pattern

## 4. Analytics

- [ ] 4.1 Disclose the daily playtime chart, the most-played-games list, and the quest-met day count as tracked
- [ ] 4.2 Disclose the session-insights summary as inferred, once for its three figures
- [ ] 4.3 Disclose the achievement-rarity breakdown as observed
- [ ] 4.4 Add the time-of-day pattern's own caveat alongside its term: a long interval between checks attributes play to the start of that interval
- [ ] 4.5 Present the peak bucket as the pattern's peak, not as an observation of when the player plays
- [ ] 4.6 Confirm no screen-level banner is introduced, and that every figure, the window selector, and the chart remain present and usable

## 5. History

- [ ] 5.1 Route the existing `UiFormat.approxTime` treatment through the shared vocabulary so the `~` prefix is the inferred term's rendering rather than a local convention — without restating the History screen requirement, which `extend-history-before-tracking` also revises
- [ ] 5.2 Make the session-start disclosure reach the same shared explanation Analytics reaches
- [ ] 5.3 Disclose a day's tracked minutes as tracked, consistently with Analytics' daily chart
- [ ] 5.4 Confirm no day total, quest state, thumbnail row, or expansion behaviour changes

## 6. Home and collection pacing

- [ ] 6.1 Disclose the now-playing elapsed time as inferred, consistently with the existing requirement that it is time since detection rather than an exact launch time
- [ ] 6.2 In `CollectionPacingPresentation`, keep the existing reliable/learning statement as the one statement presented, and do not add a provenance hedge beside it
- [ ] 6.3 Keep the provenance term reachable from the pacing surface's explanation without contradicting the reliability statement
- [ ] 6.4 Confirm a figure carrying only a confidence state gains no provenance marker merely to fill the slot

## 7. Non-regression

- [ ] 7.1 Confirm `SessionDiffer`, `GamificationUpdater`, `groupHistory`, and `AnalyticsWindow` are unmodified
- [ ] 7.2 Confirm no entity, DAO, migration, DataStore key, or Steam request was added
- [ ] 7.3 Confirm every number presented is identical to the number presented before the change
- [ ] 7.4 Confirm no provenance value is persisted per record anywhere
