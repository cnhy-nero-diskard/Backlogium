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

// Dark-first "Steam-native dark + gold accent" scheme (restyle-visual-identity).
private val DarkColorScheme = darkColorScheme(
    primary = Gold,
    onPrimary = OnGold,
    primaryContainer = GoldContainer,
    onPrimaryContainer = OnGoldContainer,
    secondary = SteelBlue,
    onSecondary = OnSteelBlue,
    tertiary = SteelBlueLight,
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
