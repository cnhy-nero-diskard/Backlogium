# gamification

## ADDED Requirements

### Requirement: Derived totals cannot silently wrap or clamp
The engine SHALL compute and return derived totals — per-game XP, total XP, and achievement
XP — without arithmetic overflow for any rule configuration the app accepts. No accepted
configuration SHALL be able to produce a stored total that is lower than the value the
mathematics defines, and in particular a total SHALL NOT arrive at zero or at a level of 1 by
way of a wrapped intermediate value.

Overflow SHALL be prevented rather than absorbed. A clamp applied after a value has already
wrapped is prohibited as the mechanism for satisfying this requirement, because it converts a
detectable fault into a plausible-looking result: a wrapped negative total clamped to zero is
indistinguishable from a player who has genuinely earned nothing.

Because the on-device engine is the sole author of these values and no external source can
reconstruct them, a recompute SHALL NOT be able to replace a previously valid total with a
wrapped or clamped one.

#### Scenario: Extreme but accepted rate does not wrap
- **WHEN** the largest per-minute XP rate the app accepts is configured and a game with
  tracked minutes is recomputed
- **THEN** the computed XP is the mathematically correct product, not a wrapped value, and the
  persisted total is not zero

#### Scenario: A large library sums without overflow
- **WHEN** XP is summed across a library large enough that the total exceeds the range of a
  single game's XP representation
- **THEN** the total is correct, because the accumulation is not narrower than the values it
  accumulates

#### Scenario: Achievement XP sums without overflow
- **WHEN** achievement XP is summed across many unlocked achievements at the largest accepted
  per-tier award
- **THEN** the total is correct and does not wrap

#### Scenario: Level derivation receives an unwrapped total
- **WHEN** a level is derived from a total XP value
- **THEN** the total it receives has not been wrapped, so the level is not reported as 1 for a
  player with substantial accumulated XP

#### Scenario: A recompute never lowers a valid total by wrapping
- **WHEN** a recompute runs over data that previously produced a valid total
- **THEN** it does not persist a lower total as a result of overflow or of clamping an
  overflowed value

### Requirement: Rule configuration is bounded at both ends
The system SHALL reject a numeric rule value that is too large to be used safely, as well as
one below its documented minimum, and SHALL do so at entry with the same inline treatment a
below-minimum value receives. An accepted configuration SHALL be one the engine can evaluate
across the player's whole library without overflow.

A floor-only validation is insufficient: a value can be a well-formed positive number, pass
every check the screen makes, and still be unusable. Rejecting it at entry is what makes the
engine's guarantee above achievable without the engine silently reinterpreting what the
player asked for.

#### Scenario: Value above the safe ceiling is refused
- **WHEN** the player enters a numeric rule value larger than the configuration can safely use
- **THEN** the field shows an inline reason and the configuration cannot be saved

#### Scenario: Below-minimum values behave as before
- **WHEN** the player enters a value below a field's documented minimum
- **THEN** it is refused with its existing inline reason, unchanged by this requirement

#### Scenario: Ordinary values are unaffected
- **WHEN** the player enters a value within the usable range
- **THEN** it is accepted, and the ceiling is high enough not to interfere with any plausible
  tuning choice

#### Scenario: An already-stored extreme value is corrected
- **WHEN** a configuration containing a value above the new ceiling was stored before the
  ceiling existed
- **THEN** the player is not left unable to use the screen, and the next recompute produces a
  correct total rather than preserving a wrapped one
