package com.example.backlogium

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.backlogium.ui.BacklogiumAppRoot
import com.example.backlogium.ui.theme.BacklogiumTheme
import com.example.backlogium.data.updates.OPEN_UPDATE_EXTRA
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableSharedFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val openUpdateRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BacklogiumTheme {
                BacklogiumAppRoot(
                    initialOpenUpdate = intent.getBooleanExtra(OPEN_UPDATE_EXTRA, false),
                    openUpdateRequests = openUpdateRequests,
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(OPEN_UPDATE_EXTRA, false)) {
            openUpdateRequests.tryEmit(Unit)
        }
    }
}
