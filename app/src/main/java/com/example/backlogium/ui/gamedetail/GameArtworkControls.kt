package com.example.backlogium.ui.gamedetail

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.backlogium.data.remote.SteamIconMapper
import com.example.backlogium.domain.GameArtworkVariant

@Composable
internal fun GameArtworkControls(
    appId: Long,
    placeholder: Boolean,
    state: ArtworkActionState,
    onSelect: (GameArtworkVariant?) -> Unit,
) {
    var chooser by rememberSaveable(appId) { mutableStateOf(false) }
    val selected = state.preference?.variant
    Column(Modifier.fillMaxWidth()) {
        if (placeholder || selected != null) {
            TextButton(onClick = { chooser = true }, enabled = state.preference != null && !state.pending) {
                Text(if (selected == null) "Choose cover" else "Manage cover")
            }
        }
        if (selected != null) {
            Text("Selected cover: ${selected.label}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onSelect(null) }, enabled = !state.pending) { Text("Reset cover") }
        }
        if (state.pending) Text("Saving cover…", style = MaterialTheme.typography.bodySmall)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    if (chooser) {
        SteamArtworkChooser(appId, selected, state.pending, onDismiss = { chooser = false },
            onSelect = { onSelect(it); chooser = false })
    }
}

@Composable
internal fun SteamArtworkChooser(
    appId: Long,
    selected: GameArtworkVariant?,
    pending: Boolean,
    onDismiss: () -> Unit,
    onSelect: (GameArtworkVariant?) -> Unit,
) {
    val context = LocalContext.current
    val online = remember {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose Steam cover") },
        text = {
            Column {
                Text(if (online) "Available Steam artwork for this game." else "Offline: only cached covers are available.")
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(GameArtworkVariant.entries, key = { it.name }) { variant ->
                        val url = SteamIconMapper.artworkUrl(appId, variant)
                        var available by remember(url) { mutableStateOf(false) }
                        var failed by remember(url) { mutableStateOf(false) }
                        val request = remember(url, online) {
                            ImageRequest.Builder(context).data(url)
                                .networkCachePolicy(if (online) CachePolicy.ENABLED else CachePolicy.DISABLED).build()
                        }
                        OutlinedCard(onClick = { onSelect(variant) }, enabled = available && !pending,
                            modifier = Modifier.fillMaxWidth().testTag("steam-cover-${variant.name}")) {
                            Column(Modifier.padding(8.dp)) {
                                SubcomposeAsyncImage(model = request, contentDescription = "${variant.label} preview",
                                    modifier = Modifier.fillMaxWidth().height(80.dp), contentScale = ContentScale.Crop,
                                    onSuccess = { available = true; failed = false },
                                    onError = { failed = true; available = false },
                                    loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading preview…") } },
                                    error = { Box(Modifier.fillMaxSize()) })
                                Text(variant.label + if (variant == selected) " · Selected" else "")
                                Text(when {
                                    available -> "Available · Tap to select"
                                    failed && !online -> "Unavailable offline"
                                    failed -> "Unavailable"
                                    else -> "Checking availability…"
                                }, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            if (selected != null) TextButton(onClick = { onSelect(null) }, enabled = !pending) { Text("Reset cover") }
        },
    )
}

internal val GameArtworkVariant.label: String get() = when (this) {
    GameArtworkVariant.HEADER -> "Store header"
    GameArtworkVariant.LIBRARY_HERO -> "Library hero"
    GameArtworkVariant.WIDE_CAPSULE -> "Wide capsule"
    GameArtworkVariant.HERO_CAPSULE -> "Hero capsule"
    GameArtworkVariant.LIBRARY_CAPSULE -> "Library capsule"
}
