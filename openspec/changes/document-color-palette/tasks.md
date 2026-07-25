# Tasks — Document the color palette in the README

> Documentation only. `ui/theme/Color.kt` stays the source of truth for values; the README
> documents and points at it.

## 1. README section
- [ ] 1.1 Add a "Visual identity" / "Color palette" section (after **Stack** or **Architecture**,
  before **Roadmap**)
- [ ] 1.2 Table per family, with hex, role, and on-color pairing:
  - Accent (gold/amber): `Gold #E0A83A`, `OnGold #241A00`, `GoldContainer #4A3A12`,
    `OnGoldContainer #F5DFA6`
  - Dark surfaces (charcoal/navy): `NavyBackground #10141C`, `NavySurface #171C26`,
    `NavySurfaceVariant #232A38`, `OnNavy #E4E8F0`, `OnNavyVariant #AEB6C4`
  - Secondary/tertiary (steel blue): `SteelBlue #7FA6C9`, `OnSteelBlue #0B1722`,
    `SteelBlueLight #9DBBD8`
  - Live/active (green): the presence token added by `enhance-now-playing` for the Library's
    running-game dot — include it if that change has landed, and leave a note to add it if it has not
  - Light scheme: `GoldLight #7A5A00`, `OnGoldLight #FFFFFF`, `GoldContainerLight #FFDF9C`,
    `OnGoldContainerLight #261A00`, `LightBackground`/`LightSurface #FBF8F1`,
    `LightSurfaceVariant #EDE6D6`, `OnLight #1B1B17`, `OnLightVariant #4C4738`,
    `SteelBlueDark #2F5B7C`
- [ ] 1.3 State the **accent rule** explicitly: gold is reserved for milestone moments (level-up,
  streak milestone, 100% completion) — not a general-purpose highlight; steel-blue/tertiary carries
  ordinary emphasis and the in-game lane; green means live presence only
- [ ] 1.4 Note that the app pins `BacklogiumTheme(dynamicColor = false)`, so this palette is the look
  on every device, and that it is dark-first with a light scheme kept for system light-mode users
- [ ] 1.5 Link `docs/ui-screens-descriptor.md` (per-screen reference) and `ui/theme/Color.kt`
  (source of truth)

## 2. Reconcile
- [ ] 2.1 Verify every hex against `ui/theme/Color.kt` — the README must not drift from the code
- [ ] 2.2 Check the palette section in `docs/ui-screens-descriptor.md` for contradictions and
  reconcile rather than duplicating
