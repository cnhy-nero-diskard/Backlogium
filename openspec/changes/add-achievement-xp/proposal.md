# Add rarity-tiered achievement XP (gamification engine)

## Why

`add-gamification-engine` deliberately deferred achievement-based XP — the engine only
awards XP for tracked playtime. But playtime alone rewards idling more than actually
finishing hard content, and a game's Steam achievements already carry a natural
difficulty signal: Steam's global unlock percentage (`GetGlobalAchievementPercentagesForApp`).
An achievement 2% of players have unlocked represents more real accomplishment than one
90% of players get for free, so it should be worth more XP. Locking this rule now, the
same way playtime XP was locked, keeps it a design decision settled and unit-tested
before it touches Room schema or UI.

## What Changes

- Extend the `gamification` engine with achievement XP, computed from discrete rarity
  **tiers** (not a continuous inverse-rarity formula) — chosen for predictability and
  boundedness over fine-grained scaling, consistent with the engine's existing
  documented-constants style.
- New `RarityTier` classification derived from an achievement's global unlock percentage:
  `COMMON` (≥50%), `UNCOMMON` (20–50%), `RARE` (5–20%), `EPIC` (1–5%), `LEGENDARY` (<1%).
- New tunable XP-per-tier constants in `RuleConfig`, with documented defaults, so tier
  payouts can be retuned without touching call sites or stored schema.
- A pure function that takes an achievement's unlocked state and global rarity percent
  and returns the XP it contributes; unlocked achievements contribute tiered XP, locked
  achievements contribute none.
- Achievement XP combines additively with existing playtime XP into the same `XpState`
  (total XP, level, progress) — one unified XP pool, not a separate currency.

## Capabilities

### New Capabilities
(none)

### Modified Capabilities
- `gamification`: adds achievement-XP requirements (rarity tiers, per-tier XP awards,
  combination with playtime XP) to the existing capability defined by
  `add-gamification-engine`. No existing requirement's behavior changes — this is
  additive.

## Impact

- **Affected code:** `gamification` module only (`RuleConfig`, new `RarityTier` enum and
  `AchievementInput`/similar value type, a new pure function alongside `xp()`). No
  Android, Retrofit, or Room imports — same boundary as the base engine.
- **Affected code (downstream, not part of this change):** `add-android-steam-app` will
  eventually need to fetch `GetGlobalAchievementPercentagesForApp` and per-player
  achievement unlock state and pass them into the engine, and persist the resulting XP —
  that wiring and schema work is out of scope here.
- **Sequencing:** depends on `add-gamification-engine`'s public surface (`RuleConfig`,
  `XpState`) already existing; extends it rather than replacing it.

## Non-goals

- **Fetching global achievement percentages or player unlock state** — that's
  `steam-sync`'s job (same boundary as tracked minutes today); this engine only
  consumes already-fetched per-achievement data.
- **Persistence** — storing per-achievement unlock history or awarded XP belongs to the
  app, not the engine.
- **UI** — achievement badges, rarity indicators, and unlock toasts belong to `app-ui`.
- **Continuous/proportional rarity scaling** — explicitly rejected in favor of tiers for
  this change; revisiting a continuous formula would be a separate proposal.
- **Retroactive rarity drift** — an achievement's global percent can shift over time as
  more players unlock it; this change does not define a re-evaluation or snapshot
  policy, deferred as an open question in design.md.
