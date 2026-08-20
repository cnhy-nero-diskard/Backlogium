package com.example.backlogium.ui.util

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * The app's complete haptic vocabulary. Callers name the moment rather than choosing a platform
 * constant, so this file is the one authority for how a given kind of feedback feels.
 *
 * Haptics accompany a moment that is already being presented; they never fire alone. A platform
 * that cannot deliver the preferred effect receives a supported fallback rather than no effect.
 * Silence is represented explicitly only where an exhaustive event mapping needs to record that
 * decision. Every other surface is silent by default.
 */
sealed interface HapticIntent {
    data object LevelUp : HapticIntent
    data object QuestMet : HapticIntent
    data object StreakMilestone : HapticIntent
    data object Confirm : HapticIntent
    data object Reject : HapticIntent
    data class Toggle(val enabled: Boolean) : HapticIntent
    data object Silent : HapticIntent
}

/** The narrow seam between an intent and the platform-backed haptic delivery. */
interface HapticPlayer {
    fun play(intent: HapticIntent)
}

/** Dispatch only real feedback; an explicit Silent intent never reaches a player implementation. */
internal fun HapticPlayer.playIfNotSilent(intent: HapticIntent) {
    if (intent !== HapticIntent.Silent) play(intent)
}

/**
 * Maps an intent to a platform constant. The nullable result is intentional: [HapticIntent.Silent]
 * must never reach [View.performHapticFeedback].
 *
 * Toggle-specific constants were added in API 34, above the app's minSdk 33. Older devices use
 * CONTEXT_CLICK for either direction, preserving a supported tactile acknowledgement instead of
 * degrading the interaction to silence.
 */
internal fun hapticConstantFor(intent: HapticIntent, sdkInt: Int): Int? = when (intent) {
    HapticIntent.LevelUp -> HapticFeedbackConstants.CONFIRM
    HapticIntent.QuestMet -> HapticFeedbackConstants.CONTEXT_CLICK
    HapticIntent.StreakMilestone -> HapticFeedbackConstants.GESTURE_END
    HapticIntent.Confirm -> HapticFeedbackConstants.CONFIRM
    HapticIntent.Reject -> HapticFeedbackConstants.REJECT
    is HapticIntent.Toggle -> if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        if (intent.enabled) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
    } else {
        HapticFeedbackConstants.CONTEXT_CLICK
    }
    HapticIntent.Silent -> null
}

private class ViewHapticPlayer(private val view: View) : HapticPlayer {
    override fun play(intent: HapticIntent) {
        hapticConstantFor(intent, Build.VERSION.SDK_INT)?.let(view::performHapticFeedback)
    }
}

/** Call sites depend on this accessor, never on [LocalView] or platform haptic constants. */
@Composable
fun rememberHaptics(): HapticPlayer {
    val view = LocalView.current
    return remember(view) { ViewHapticPlayer(view) }
}
