# Tasks — rarity-tiered achievement XP

## 1. Types & config

- [ ] 1.1 `RarityTier` enum (`COMMON`, `UNCOMMON`, `RARE`, `EPIC`, `LEGENDARY`)
- [ ] 1.2 `AchievementInput` value type (`id`, `unlocked`, `globalUnlockPercent`)
- [ ] 1.3 Add per-tier XP fields to `RuleConfig` with documented defaults
  (`commonAchievementXp`, `uncommonAchievementXp`, `rareAchievementXp`,
  `epicAchievementXp`, `legendaryAchievementXp`)

## 2. Computations (pure functions)

- [ ] 2.1 `tierFor(globalUnlockPercent)` — boundary values resolve to the more-common
  (higher) tier (50/20/5/1 cut points)
- [ ] 2.2 `achievementXp(achievements, cfg)` — sum of tiered XP for unlocked
  achievements only; locked achievements contribute zero; empty list returns zero
- [ ] 2.3 Extend `xp(totalMinutes, cfg)` to `xp(totalMinutes, achievements = emptyList(), cfg)`,
  combining playtime XP and achievement XP additively before deriving level via the
  existing `levelState`

## 3. Tests (JVM unit)

- [ ] 3.1 `tierFor`: each tier's interior value, and exact boundaries (50%, 20%, 5%, 1%)
  resolve to the higher tier
- [ ] 3.2 `achievementXp`: single unlocked achievement per tier, locked achievement
  contributes zero, mixed-tier list sums correctly, empty list is zero
- [ ] 3.3 `xp(...)` with achievements: combined total matches playtime XP + achievement
  XP; omitting achievements matches pre-existing playtime-only behavior exactly
  (regression check against current `xp()` tests)
- [ ] 3.4 `RuleConfig` overrides: non-default per-tier XP values are honored

## 4. Handoff

- [ ] 4.1 Update KDoc on the public surface so `add-android-steam-app` can wire in
  Steam's `GetGlobalAchievementPercentagesForApp` data later
- [ ] 4.2 Confirm the module still compiles with no Android classpath (boundary
  unchanged by this addition)
