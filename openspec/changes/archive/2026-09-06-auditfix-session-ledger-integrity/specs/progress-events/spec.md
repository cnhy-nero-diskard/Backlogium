# progress-events

## ADDED Requirements

### Requirement: Administrative removal of tracked history is not earned progress
When the player removes a game and the system deletes that game's tracked state and rewrites
affected daily progress, the recompute that persists the resulting derived values SHALL
declare a non-earned provenance. Removal is a bookkeeping action the player took in a
settings surface, not play; the derived values it produces SHALL reseed the delivery baseline
and SHALL produce no progress events, in either direction.

The provenance SHALL be distinct from the earned-through-play provenance rather than reusing
it, because the earned provenance is the single signal the event detector uses to decide
whether a transition is player-facing. A removal that borrows it cannot be told apart from a
sync afterwards.

This applies to derived state moving in either direction. Removing a game with substantial
recorded history can lower a level, break a streak, or invalidate a quest; those changes are
real and should be reflected, but they are not events the player earned and SHALL NOT be
delivered as such.

#### Scenario: Removal produces no progress events
- **WHEN** removing a game changes the player's level, streak, or quest state
- **THEN** no progress event is produced for that change

#### Scenario: Removal reseeds the delivery baseline
- **WHEN** a removal recompute persists derived values
- **THEN** the delivery baseline is set to the values written, including where they are lower
  than the baseline they replace

#### Scenario: Removal does not borrow earned provenance
- **WHEN** a removal recompute persists derived values
- **THEN** the declared provenance is distinguishable from the provenance a scheduled or
  manual sync declares

#### Scenario: Removal does not resurrect acknowledged history
- **WHEN** a removal lowers derived values below a previously acknowledged threshold
- **THEN** no acknowledgement baseline moves backwards, so already-acknowledged history does
  not become deliverable again

#### Scenario: Reversing a removal is equally non-earned
- **WHEN** the player reverses a removal and the restored game's history raises derived values
  again
- **THEN** that recompute also declares non-earned provenance and produces no events, because
  the player did not earn the restored progress by playing

#### Scenario: Play after a removal is still earned
- **WHEN** the player plays a tracked game after an unrelated removal has occurred
- **THEN** that sync declares earned provenance and produces events normally, measured against
  the baseline the removal reseeded
