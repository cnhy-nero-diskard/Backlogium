package com.example.backlogium.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.backlogium.R

// Display font for large numeral moments only — Level N, streak count, XP totals
// (restyle-visual-identity). Orbitron is a geometric display face (SIL OFL, bundled at
// res/font/orbitron.ttf; license at docs/licenses/Orbitron-OFL.txt). Bundled rather than
// a Downloadable Font so the offline-first guarantee is unaffected. Body/caption text
// stays on FontFamily.Default.
val DisplayFontFamily = FontFamily(
    Font(R.font.orbitron, FontWeight.Bold),
)

private val defaults = Typography()

val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    // Numeral-bearing headline styles use the display font. These are the styles the Home
    // screen uses for "Level N" (headlineMedium) and the streak count (headlineSmall).
    headlineMedium = defaults.headlineMedium.copy(fontFamily = DisplayFontFamily),
    headlineSmall = defaults.headlineSmall.copy(fontFamily = DisplayFontFamily),
)
