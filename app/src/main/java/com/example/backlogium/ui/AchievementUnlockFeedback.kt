package com.example.backlogium.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.backlogium.data.achievement.AchievementUnlockDispatcher
import com.example.backlogium.data.achievement.unlockGroupText
import com.example.backlogium.domain.AchievementUnlockGroup
import com.example.backlogium.ui.util.HapticIntent
import com.example.backlogium.ui.util.rememberHaptics
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class AchievementUnlockViewModel @Inject constructor(private val delivery: AchievementUnlockDispatcher) : ViewModel() {
    val current = MutableStateFlow<AchievementUnlockGroup?>(null)
    private val dismissals = Channel<Unit>(Channel.CONFLATED)
    init {
        viewModelScope.launch {
            delivery.foregroundGroups.collect { group ->
                if (delivery.allowed(group)) {
                    current.value = group
                    dismissals.receive()
                    current.value = null
                }
            }
        }
    }
    fun dismiss() { dismissals.trySend(Unit) }
    suspend fun canOpen(account: String, appId: Long) = delivery.canOpen(account, appId)
}

@Composable
internal fun AchievementUnlockFeedback(
    onOpenGame: (String, Long) -> Unit,
    viewModel: AchievementUnlockViewModel = hiltViewModel(),
) {
    val group by viewModel.current.collectAsStateWithLifecycle()
    group?.let { event ->
        AchievementUnlockDialog(event, viewModel::dismiss) {
            viewModel.dismiss()
            onOpenGame(event.account, event.appId)
        }
    }
}

/** No motion is required to read the exact group or activate its game-detail action. */
@Composable
internal fun AchievementUnlockDialog(group: AchievementUnlockGroup, onDismiss: () -> Unit, onOpen: () -> Unit) {
    val haptics = rememberHaptics()
    LaunchedEffect(group.eventId) {
        withFrameNanos { }
        haptics.play(HapticIntent.AchievementUnlock)
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(group.gameName) },
        text = { Text(unlockGroupText(group), Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onOpen) { Text("View achievements") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Dismiss") } })
}
