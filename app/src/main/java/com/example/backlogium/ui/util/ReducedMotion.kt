package com.example.backlogium.ui.util

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * True when the platform reports a reduced-motion preference — the user has turned animations
 * off in Accessibility / Developer options, which zeroes `ANIMATOR_DURATION_SCALE`.
 *
 * Shared by every surface that would otherwise animate continuously, so the app gives one
 * answer to the question rather than one per feature. Callers must degrade to a *static* cue,
 * never to nothing: motion may not be the only thing carrying the state.
 *
 * Read once per composition context and remembered: the setting is a system-level toggle that
 * restarts the activity when changed, so polling it on recomposition would buy nothing.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scale = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f)
        scale == 0f
    }
}
