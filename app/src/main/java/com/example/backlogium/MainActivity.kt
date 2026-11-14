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
    private val openAchievementRequests = MutableSharedFlow<Pair<String, Long>>(extraBufferCapacity = 1)
    private fun achievementDestination(intent: android.content.Intent): Pair<String, Long>? {
        val account = intent.getStringExtra(com.example.backlogium.data.achievement.OPEN_ACHIEVEMENT_ACCOUNT)?.takeIf { it.isNotBlank() } ?: return null
        val appId = intent.getLongExtra(com.example.backlogium.data.achievement.OPEN_ACHIEVEMENT_GAME, 0)
        return if (appId > 0) account to appId else null
    }

    private val openUpdateRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BacklogiumTheme {
                BacklogiumAppRoot(
                    initialOpenUpdate = intent.getBooleanExtra(OPEN_UPDATE_EXTRA, false),
                    openUpdateRequests = openUpdateRequests,
                    initialOpenAchievement = achievementDestination(intent),
                    openAchievementRequests = openAchievementRequests,
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        achievementDestination(intent)?.let { openAchievementRequests.tryEmit(it) }
        if (intent.getBooleanExtra(OPEN_UPDATE_EXTRA, false)) {
            openUpdateRequests.tryEmit(Unit)
        }
    }
}
