package com.example.backlogium.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Asks for `POST_NOTIFICATIONS` once, at first launch.
 *
 * The permission was declared in the manifest but never requested, so on a fresh install the
 * ongoing now-playing notification silently no-opped (`PresenceNotifications.update` gates on the
 * grant) until the user found the system settings toggle unaided.
 *
 * Fired here rather than when a game is first detected: detection typically happens while the
 * player is in a game and not looking at the phone, which is the worst possible moment to raise a
 * system dialog. No rationale screen — a single standard request, and denial simply means no
 * notification; presence tracking itself is unaffected either way.
 *
 * No `Build.VERSION` gate: `minSdk` is 33, so the runtime grant always applies.
 */
@Composable
fun NotificationPermissionRequest(
    viewModel: NotificationPermissionViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val alreadyRequested by viewModel.alreadyRequested.collectAsStateWithLifecycle()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* Granted or not, we asked — the recorded flag below is what stops a re-prompt. */ }

    LaunchedEffect(alreadyRequested) {
        // Null means the stored answer hasn't loaded yet; asking on that default risks prompting
        // someone who already declined.
        if (alreadyRequested != false) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.markRequested()
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
