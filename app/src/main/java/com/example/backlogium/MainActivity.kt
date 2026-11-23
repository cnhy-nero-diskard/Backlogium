package com.example.backlogium

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.backlogium.data.updates.OPEN_UPDATE_EXTRA
import com.example.backlogium.ui.BacklogiumAppRoot
import com.example.backlogium.ui.navigation.GameNotificationViewModel
import com.example.backlogium.ui.theme.BacklogiumTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableSharedFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val gameNotifications: GameNotificationViewModel by viewModels()
    private val openUpdateRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) GameNotificationIntent.read(intent)?.let(gameNotifications::request)
        enableEdgeToEdge()
        setContent {
            BacklogiumTheme {
                BacklogiumAppRoot(
                    gameNotifications = gameNotifications,
                    initialOpenUpdate = intent.getBooleanExtra(OPEN_UPDATE_EXTRA, false),
                    openUpdateRequests = openUpdateRequests,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        GameNotificationIntent.read(intent)?.let(gameNotifications::request)
        if (intent.getBooleanExtra(OPEN_UPDATE_EXTRA, false)) {
            openUpdateRequests.tryEmit(Unit)
        }
    }
}
