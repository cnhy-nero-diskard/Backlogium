# Tasks — rarity-tiered achievement XP

## 1. Types & config

- [x] 1.1 `RarityTier` enum (`COMMON`, `UNCOMMON`, `RARE`, `EPIC`, `LEGENDARY`)
- [x] 1.2 `AchievementInput` value type (`id`, `unlocked`, `globalUnlockPercent: Double?`
  — null means Steam has no global stat)
- [x] 1.3 Add per-tier XP fields to `RuleConfig` with documented defaults
  (`commonAchievementXp`, `uncommonAchievementXp`, `rareAchievementXp`,
  `epicAchievementXp`, `legendaryAchievementXp`)

## 2. Computations (pure functions)

- [x] 2.1 `tierFor(globalUnlockPercent)` — boundary values resolve to the more-common
  (higher) tier (50/20/5/1 cut points)
- [x] 2.2 `achievementXp(achievements, cfg)` — sum of tiered XP for unlocked
  achievements only; locked achievements contribute zero; achievements with a null
  `globalUnlockPercent` contribute zero (un-tierable); empty list returns zero
- [x] 2.3 Extend `xp(games, cfg)` to `xp(games, achievements = emptyList(), cfg)`,
  combining the summed per-game playtime XP and achievement XP additively before
  deriving level via the existing `levelState`

## 3. Tests (JVM unit)

- [x] 3.1 `tierFor`: each tier's interior value, and exact boundaries (50%, 20%, 5%, 1%)
  resolve to the higher tier
- [x] 3.2 `achievementXp`: single unlocked achievement per tier, locked achievement
  contributes zero, null-percent achievement contributes zero, `0.0` percent tiers as
  `LEGENDARY`, mixed-tier list sums correctly, empty list is zero
- [x] 3.3 `xp(...)` with achievements: combined total matches summed per-game playtime XP
  + achievement XP; omitting achievements matches the base engine's playtime-only
  behavior exactly (regression check against `add-gamification-engine`'s `xp()` tests)
- [x] 3.4 `RuleConfig` overrides: non-default per-tier XP values are honored

## 4. Handoff

- [x] 4.1 Update KDoc on the public surface so `add-android-steam-app` can wire in
  Steam's `GetGlobalAchievementPercentagesForApp` data later
- [x] 4.2 Confirm the module still compiles with no Android classpath (boundary
  unchanged by this addition)
