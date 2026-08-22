## 1. The selector

- [ ] 1.1 Add a pure selector in `domain/` taking eligible members, the previous result, and an injected randomness source, returning the selected member — no Android, no Room, no injection, following `SmartCollections` and `CollectionSummary.derive`
- [ ] 1.2 Implement uniform selection over the eligible pool
- [ ] 1.3 Implement the no-immediate-repeat rule: exclude the previous result whenever another eligible member exists, and return it when it is the only one
- [ ] 1.4 Take randomness as a parameter rather than reading a global source, so selection is testable without repeated sampling
- [ ] 1.5 Unit-test: reproducibility for a given source; the previous result never returned while alternatives exist; a two-member pool alternating; a one-member pool returning that member; the third spin being free to repeat the first

## 2. Eligibility

- [ ] 2.1 Derive the eligible pool as every member except ordered-queue members marked done
- [ ] 2.2 Do not exclude on achievement completion, playtime, completion length, or recency — assert this with a fixture whose members differ on all four
- [ ] 2.3 Expose both the eligible count and the collection's total membership, so the surface can state the pool and explain a shortfall
- [ ] 2.4 Recompute eligibility as membership and done-marks change, so the roulette appears and disappears at the two-member threshold

## 3. Result presentation

- [ ] 3.1 Present the selected game's name and art, the pool size chosen from, and — when the pool is smaller than the membership — why the rest were excluded
- [ ] 3.2 Offer opening the game and spinning again
- [ ] 3.3 Convey that the choice was arbitrary, not a recommendation
- [ ] 3.4 Tint with the collection's stored accent; verify the milestone accent is not used anywhere in the surface
- [ ] 3.5 Announce the selected game, the pool size, and each action to accessibility services
- [ ] 3.6 Dismissing without acting changes nothing and records nothing

## 4. The action slot

- [ ] 4.1 For an ordered queue, offer making the selected game the queue's next game; persist the sequence through the existing collection ordering path and confirm the next-game surface updates
- [ ] 4.2 Deliver the existing committed-action success intent on that commit, and nothing on opening or spinning
- [ ] 4.3 For every other mode, render no slot at all — no disabled control and no placeholder for the launch verb that does not exist yet
- [ ] 4.4 Structure the slot so a later change can add an action without reshaping the result surface, and add no coupling to the desktop agent in either direction

## 5. Motion

- [ ] 5.1 Implement the spin as decoration over the already-decided result — the selection happens before the animation, never as its outcome
- [ ] 5.2 Under a reduced-motion preference, reveal the result directly with no spin and no substituted animation
- [ ] 5.3 Verify the result is complete and legible with the animation removed entirely
- [ ] 5.4 Verify no haptic is delivered by spinning or revealing

## 6. Non-regression

- [ ] 6.1 Confirm no entity, DAO, migration, or DataStore key was added, and no Steam request
- [ ] 6.2 Confirm no roulette outcome is persisted, and that the previous result is forgotten on leaving the collection
- [ ] 6.3 Confirm the haptic vocabulary is unchanged — no new intent was added
- [ ] 6.4 Confirm collection membership, modes, accents, ordering, pacing, and Home's collection cards are otherwise untouched
