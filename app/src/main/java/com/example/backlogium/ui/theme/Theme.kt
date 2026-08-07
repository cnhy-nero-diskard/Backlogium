package com.example.backlogium.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import com.example.backlogium.domain.CollectionAccent
import com.example.backlogium.gamification.RarityTier

// Dark-first "Steam-native dark + gold accent" scheme (restyle-visual-identity).
private val DarkColorScheme = darkColorScheme(
    primary = Gold,
    onPrimary = OnGold,
    primaryContainer = GoldContainer,
    onPrimaryContainer = OnGoldContainer,
    secondary = SteelBlue,
    onSecondary = OnSteelBlue,
    tertiary = SteelBlueLight,
    tertiaryContainer = SteelBlueContainer,
    onTertiaryContainer = OnSteelBlueContainer,
    background = NavyBackground,
    onBackground = OnNavy,
    surface = NavySurface,
    onSurface = OnNavy,
    surfaceVariant = NavySurfaceVariant,
    onSurfaceVariant = OnNavyVariant,
)

private val LightColorScheme = lightColorScheme(
    primary = GoldLight,
    onPrimary = OnGoldLight,
    primaryContainer = GoldContainerLight,
    onPrimaryContainer = OnGoldContainerLight,
    secondary = SteelBlueDark,
    tertiary = SteelBlueDark,
    tertiaryContainer = SteelBlueContainerLight,
    onTertiaryContainer = OnSteelBlueContainerLight,
    background = LightBackground,
    onBackground = OnLight,
    surface = LightSurface,
    onSurface = OnLight,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = OnLightVariant,
)

/**
 * Fill for the portion of a completion bar beyond the HowLongToBeat length.
 *
 * An extension rather than a scheme slot: Material has no role for "the accent, darker and
 * redder", and the nearest candidates lie about the meaning — `error` reads as a fault, `tertiary`
 * is the unrelated steel-blue. Keyed off surface luminance rather than `isSystemInDarkTheme()` so
 * it follows the scheme actually in force, including a caller that pins [BacklogiumTheme]'s
 * `darkTheme` or turns dynamic color on.
 */
val ColorScheme.overrunExcess: Color
    get() = if (surface.luminance() < 0.5f) GoldOverrun else GoldOverrunLight

/** Orange used for a deadline that is approaching but has not arrived. */
val ColorScheme.deadlineWarning: Color
    get() = if (surface.luminance() < 0.5f) DeadlineWarning else DeadlineWarningLight

/**
 * The glow color for an achievement icon's rarity halo (enhance-game-detail) — Steam's own
 * "shiny" achievement treatment, color-coded per tier here rather than one fixed shine. LEGENDARY
 * reuses [Gold] and RARE reuses [SteelBlue] rather than introducing separate tokens, so gold keeps
 * meaning the same thing (a milestone) everywhere it appears.
 *
 * Keyed off surface luminance like [overrunExcess], not `isSystemInDarkTheme()`, so it follows
 * whichever scheme is actually in force.
 */
fun ColorScheme.rarityHalo(tier: RarityTier): Color {
    val dark = surface.luminance() < 0.5f
    return when (tier) {
        RarityTier.COMMON -> if (dark) RarityCommon else RarityCommonLight
        RarityTier.UNCOMMON -> if (dark) RarityUncommon else RarityUncommonLight
        RarityTier.RARE -> if (dark) SteelBlue else SteelBlueDark
        RarityTier.EPIC -> if (dark) RarityEpic else RarityEpicLight
        RarityTier.LEGENDARY -> if (dark) Gold else GoldLight
    }
}

/**
 * The "currently playing" dot drawn on a library row's game icon. Keyed off surface luminance
 * like [overrunExcess] and [rarityHalo], not `isSystemInDarkTheme()`, so it follows whichever
 * scheme is actually in force.
 */
val ColorScheme.playingIndicator: Color
    get() = if (surface.luminance() < 0.5f) PlayingIndicator else PlayingIndicatorLight

/**
 * The accent color for a collection card or picker chip (refine-collections-ui). Returns the
 * palette token keyed off the current surface luminance, and `surfaceVariant` for the default
 * / no-accent state. The result tints the icon chip, progress indicator, card surface wash, and
 * start-edge stripe.
 */
fun ColorScheme.collectionAccentColor(accent: CollectionAccent?): Color {
    if (accent == null) return surfaceVariant
    val dark = surface.luminance() < 0.5f
    return when (accent) {
        CollectionAccent.STEEL_BLUE -> if (dark) SteelBlue else SteelBlueDark
        CollectionAccent.VIOLET -> if (dark) RarityEpic else RarityEpicLight
        CollectionAccent.SAGE -> if (dark) RarityUncommon else RarityUncommonLight
        CollectionAccent.SLATE -> if (dark) RarityCommon else RarityCommonLight
        CollectionAccent.TEAL -> if (dark) CollectionTeal else CollectionTealLight
        CollectionAccent.ROSE -> if (dark) CollectionRose else CollectionRoseLight
        CollectionAccent.CORAL -> if (dark) CollectionCoral else CollectionCoralLight
    }
}

@Composable
fun BacklogiumTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic (wallpaper-derived) color is intentionally OFF by default so the custom
    // Steam-native identity is the app's look on every device (restyle-visual-identity).
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
