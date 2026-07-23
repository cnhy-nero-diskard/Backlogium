package com.example.backlogium.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.example.backlogium.R

// Display font for large numeral moments only — Level N, streak count, XP totals
// (restyle-visual-identity). Orbitron is a geometric display face (SIL OFL, bundled at
// res/font/orbitron.ttf; license at docs/licenses/Orbitron-OFL.txt). Bundled rather than
// a Downloadable Font so the offline-first guarantee is unaffected.
val DisplayFontFamily = FontFamily(
    Font(R.font.orbitron, FontWeight.Bold),
)

// Brand body font for everything else — body, caption, title, label, and nav text
// (restyle-fixes). Space Grotesk is a proportional geometric sans (SIL OFL, bundled at
// res/font/space_grotesk_*.ttf; license at docs/licenses/SpaceGrotesk-OFL.txt). Bundled as
// static weights rather than a Downloadable Font so the offline-first guarantee is
// unaffected. Applying it across the full Typography below is what keeps any text from
// falling back to the platform system default (Roboto).
val SpaceGroteskFontFamily = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)

private val defaults = Typography()

// Start from the Material3 defaults but retarget every style onto the brand body font, then
// hand the numeral-bearing headline styles back to the display font. Copying *all* styles
// (rather than a handful) is what closes the system-default fallback gap: Home, Library,
// History, dialogs, and the bottom nav all render in Space Grotesk, while "Level N"
// (headlineMedium) and the streak count (headlineSmall) stay on Orbitron.
val Typography = Typography(
    displayLarge = defaults.displayLarge.copy(fontFamily = SpaceGroteskFontFamily),
    displayMedium = defaults.displayMedium.copy(fontFamily = SpaceGroteskFontFamily),
    displaySmall = defaults.displaySmall.copy(fontFamily = SpaceGroteskFontFamily),
    headlineLarge = defaults.headlineLarge.copy(fontFamily = SpaceGroteskFontFamily),
    headlineMedium = defaults.headlineMedium.copy(fontFamily = DisplayFontFamily),
    headlineSmall = defaults.headlineSmall.copy(fontFamily = DisplayFontFamily),
    titleLarge = defaults.titleLarge.copy(fontFamily = SpaceGroteskFontFamily),
    titleMedium = defaults.titleMedium.copy(fontFamily = SpaceGroteskFontFamily),
    titleSmall = defaults.titleSmall.copy(fontFamily = SpaceGroteskFontFamily),
    bodyLarge = defaults.bodyLarge.copy(fontFamily = SpaceGroteskFontFamily),
    bodyMedium = defaults.bodyMedium.copy(fontFamily = SpaceGroteskFontFamily),
    bodySmall = defaults.bodySmall.copy(fontFamily = SpaceGroteskFontFamily),
    labelLarge = defaults.labelLarge.copy(fontFamily = SpaceGroteskFontFamily),
    labelMedium = defaults.labelMedium.copy(fontFamily = SpaceGroteskFontFamily),
    labelSmall = defaults.labelSmall.copy(fontFamily = SpaceGroteskFontFamily),
)
