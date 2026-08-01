package com.example.backlogium.ui.theme

import androidx.compose.ui.graphics.Color

// Backlogium "Steam-native dark" palette (restyle-visual-identity).
// Charcoal/near-navy surface family evocative of Steam's client without cloning its
// hex values, plus a single gold/amber accent. Chosen against Material 3 contrast
// guidance so on-color pairs stay legible (see docs/ui-screens-descriptor.md).

// --- Accent (gold/amber) --------------------------------------------------------
/** Primary accent. Dark, saturated gold that carries >4.5:1 contrast on the dark surfaces. */
val Gold = Color(0xFFE0A83A)
/** Text/icon color drawn on top of [Gold]; near-black for strong contrast on the amber fill. */
val OnGold = Color(0xFF241A00)
/** Muted gold container for tonal surfaces (e.g. progress track, subtle highlights). */
val GoldContainer = Color(0xFF4A3A12)
val OnGoldContainer = Color(0xFFF5DFA6)

/**
 * The same accent pushed darker and redder — the "played past it" portion of a completion bar,
 * drawn as the track beneath the gold fill so the bar stays full width.
 *
 * Deliberately not `error`: overshooting a completion estimate is a surplus, not a fault, and a
 * pure red would say the opposite. Staying inside the gold hue family keeps it legible as *more of
 * the same bar* rather than a second, unrelated measurement.
 */
val GoldOverrun = Color(0xFF8A431C)

// --- Dark surface family (charcoal/navy) ----------------------------------------
val NavyBackground = Color(0xFF10141C)   // app background — deep charcoal-navy
val NavySurface = Color(0xFF171C26)      // cards / elevated surfaces
val NavySurfaceVariant = Color(0xFF232A38) // secondary surface / dividers
val OnNavy = Color(0xFFE4E8F0)           // primary text on dark
val OnNavyVariant = Color(0xFFAEB6C4)    // captions / secondary text on dark

// --- Secondary / tertiary (cool steel-blue, kept subordinate to gold) -----------
val SteelBlue = Color(0xFF7FA6C9)
val OnSteelBlue = Color(0xFF0B1722)
val SteelBlueLight = Color(0xFF9DBBD8)

/**
 * Tertiary container pair for the now-playing card (enhance-now-playing) — Material 3's
 * `darkColorScheme()` default tertiary container is an unrelated baseline purple when left
 * unset, so this is hand-tuned from [SteelBlue] the same way [GoldContainer] is tuned from
 * [Gold], keeping the card's "in game right now" lane visually distinct from gold's milestones.
 */
val SteelBlueContainer = Color(0xFF243B4C)
val OnSteelBlueContainer = Color(0xFFCFE4F0)

// --- Light scheme (kept for system light-mode users; dark-first design) ----------
val GoldLight = Color(0xFF7A5A00)
val OnGoldLight = Color(0xFFFFFFFF)
val GoldContainerLight = Color(0xFFFFDF9C)
val OnGoldContainerLight = Color(0xFF261A00)
val LightBackground = Color(0xFFFBF8F1)
val LightSurface = Color(0xFFFBF8F1)
val LightSurfaceVariant = Color(0xFFEDE6D6)
val OnLight = Color(0xFF1B1B17)
val OnLightVariant = Color(0xFF4C4738)
val SteelBlueDark = Color(0xFF2F5B7C)

/** Light-scheme counterpart to [GoldOverrun]: burnt amber, legible on the cream surfaces. */
val GoldOverrunLight = Color(0xFFB4571F)

/** Light-scheme counterpart to [SteelBlueContainer]/[OnSteelBlueContainer]. */
val SteelBlueContainerLight = Color(0xFFD3E4F0)
val OnSteelBlueContainerLight = Color(0xFF12283A)

// --- Achievement rarity halo (enhance-game-detail) -------------------------------
// A dull-to-vivid ramp for the glow behind an unlocked achievement's icon, evoking Steam's own
// "shiny" treatment but color-coded per tier. COMMON and UNCOMMON get new, deliberately muted
// hues; RARE and LEGENDARY reuse [SteelBlue] and [Gold] rather than adding two more tokens, which
// also keeps gold reserved for the same "milestone" role it already has (streaks, the completed
// banner) instead of splitting that meaning across two shades of gold.
/** COMMON halo: a dim slate — barely a glow, so it doesn't compete with rarer tiers nearby. */
val RarityCommon = Color(0xFF8A93A3)
val RarityCommonLight = Color(0xFF5B6472)

/** UNCOMMON halo: a soft sage — a step up from COMMON without reaching for a saturated hue. */
val RarityUncommon = Color(0xFF6FAE7A)
val RarityUncommonLight = Color(0xFF3F7A4C)

/** EPIC halo: violet — between RARE's cool steel-blue and LEGENDARY's gold on the ramp. */
val RarityEpic = Color(0xFFA579D6)
val RarityEpicLight = Color(0xFF6B3FA0)

// --- Live status (currently-playing indicator) -----------------------------------
/** "Currently playing" dot on a library row. A vivid, unambiguous green — distinct from the
 *  muted sage used for [RarityUncommon] so the two don't get read as the same signal. */
val PlayingIndicator = Color(0xFF4ADE80)
val PlayingIndicatorLight = Color(0xFF15803D)
