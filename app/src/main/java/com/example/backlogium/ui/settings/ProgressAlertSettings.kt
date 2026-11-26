package com.example.backlogium.ui.settings

import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.backlogium.R
import com.example.backlogium.data.notifications.ProgressCategory

@Composable
internal fun ProgressAlertSettings(preferences: Map<ProgressCategory, Boolean>, onChanged: (ProgressCategory, Boolean) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val notificationsAllowed = remember(revision) {
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.progress_alerts_title), style = MaterialTheme.typography.titleMedium)
            if (!notificationsAllowed) {
                Text(stringResource(R.string.progress_alerts_permission_off))
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text(stringResource(R.string.progress_alerts_system_settings)) }
            }
            ProgressCategory.entries.forEach { category ->
                val channel = remember(category, revision) {
                    context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(category.channelId)
                }
                val label = stringResource(category.label())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f))
                    Switch(modifier = Modifier.semantics { contentDescription = label },
                        checked = preferences[category] ?: category.defaultEnabled,
                        onCheckedChange = { onChanged(category, it) })
                }
                Text(stringResource(if (channel?.importance == NotificationManager.IMPORTANCE_NONE)
                    R.string.progress_alerts_channel_off else if (channel?.shouldVibrate() == true)
                    R.string.progress_alerts_vibration_on else R.string.progress_alerts_vibration_off),
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, category.channelId))
                }) { Text(stringResource(R.string.progress_alerts_channel_settings)) }
            }
        }
    }
}

private fun ProgressCategory.label(): Int = when (this) {
    ProgressCategory.OWNED -> R.string.progress_alerts_owned
    ProgressCategory.SHARED -> R.string.progress_alerts_shared
    ProgressCategory.STREAK -> R.string.progress_alerts_streak
    ProgressCategory.QUOTA -> R.string.progress_alerts_quota
    ProgressCategory.COMPLETION -> R.string.progress_alerts_completion
    ProgressCategory.RECAP -> R.string.progress_alerts_recap
}
