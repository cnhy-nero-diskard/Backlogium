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
