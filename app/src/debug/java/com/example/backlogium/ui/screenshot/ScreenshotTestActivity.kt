package com.example.backlogium.ui.screenshot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable

/** Debug-only host for local Compose tests; recreation tests can install content during onCreate. */
class ScreenshotTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recreationContent?.let { content -> setContent { content() } }
    }

    companion object {
        /** Optional test content installed from onCreate for Activity state-restoration tests. */
        var recreationContent: (@Composable () -> Unit)? = null
    }
}
