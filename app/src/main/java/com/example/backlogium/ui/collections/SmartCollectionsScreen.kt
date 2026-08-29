package com.example.backlogium.ui.collections

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.backlogium.domain.SmartCollectionId
import com.example.backlogium.ui.components.GameListDensityControl
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowBack
import compose.icons.tablericons.DeviceGamepad
import compose.icons.tablericons.Settings

/** The pushed Collections index: custom collections and derived observations have separate groups. */
@Composable
fun CollectionsScreen(
    onDone: () -> Unit,
    onCreateCustomCollection: () -> Unit,
    onOpenCustomCollection: (Long) -> Unit,
    onOpenSmartCollection: (SmartCollectionId) -> Unit,
    viewModel: SmartCollectionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showVisibilityDialog by rememberSaveable { mutableStateOf(false) }

    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        CollectionsHeader(
            onDone = onDone,
            onManageVisibility = { showVisibilityDialog = true },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { CustomCollectionsSectionHeader(onCreateCustomCollection) }
            if (state.customCollections.isEmpty()) {
                item {
                    CollectionEmptyCard(
                        title = "No custom collections yet",
                        message = "Create a collection for the games you want to group yourself.",
                        actionLabel = "New collection",
                        onAction = onCreateCustomCollection,
                    )
                }
            } else {
                items(state.customCollections, key = { "custom-${it.id}" }) { collection ->
                    CustomCollectionCard(
                        collection = collection,
                        onClick = { onOpenCustomCollection(collection.id) },
                    )
                }
            }

            if (state.visibleSmartCollections.isNotEmpty()) {
                item { SmartCollectionsSectionHeader() }
                item {
                    Text(
                        text = SMART_COLLECTION_MISSING_HLTB_NOTE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(state.visibleSmartCollections, key = { "smart-${it.id}" }) { collection ->
                    SmartCollectionCard(
                        collection = collection,
                        onClick = { onOpenSmartCollection(collection.id) },
                    )
                }
            }

            if (state.customCollections.isEmpty() && state.visibleSmartCollections.isEmpty()) {
                item {
                    Text(
                        text = "Derived lists appear when your local library has enough data to qualify games.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            item { Spacer(Modifier.size(16.dp)) }
        }
    }

    if (showVisibilityDialog) {
        SmartCollectionVisibilityDialog(
            collections = state.smartCollections.filter { it.members.isNotEmpty() },
            onVisibleChanged = viewModel::setSmartCollectionVisible,
            onDismiss = { showVisibilityDialog = false },
        )
    }
}

@Composable
private fun CollectionsHeader(
    onDone: () -> Unit,
    onManageVisibility: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDone) {
            Icon(TablerIcons.ArrowBack, contentDescription = "Back")
        }
        Text(
            text = "Collections",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onManageVisibility,
            modifier = Modifier.semantics { contentDescription = "Manage derived collections" },
        ) {
            Icon(TablerIcons.Settings, contentDescription = "Manage derived collections")
        }
    }
}

@Composable
private fun CustomCollectionsSectionHeader(onCreate: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Custom collections",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCreate) { Text("New") }
    }
}

@Composable
private fun SmartCollectionsSectionHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = TablerIcons.DeviceGamepad,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text("Derived collections", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun CustomCollectionCard(
    collection: CustomCollectionCardUi,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Open ${collection.name} collection" },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(collection.name, style = MaterialTheme.typography.titleSmall)
            Text(
                text = "${collection.memberCount} ${if (collection.memberCount == 1) "game" else "games"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            collection.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SmartCollectionCard(
    collection: SmartCollectionCardUi,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Open ${collection.name} derived collection" },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = collection.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${collection.members.size}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = collection.rule,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun CollectionEmptyCard(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(message, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun SmartCollectionVisibilityDialog(
    collections: List<SmartCollectionCardUi>,
    onVisibleChanged: (SmartCollectionId, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Derived collections") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Choose which non-empty derived lists appear on Collections.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                collections.forEach { collection ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(collection.name)
                            Text(
                                text = "${collection.members.size} games",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = !collection.hidden,
                            onCheckedChange = { visible ->
                                onVisibleChanged(collection.id, visible)
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** Read-only detail for a derived list; it intentionally has no custom-collection actions. */
@Composable
fun SmartCollectionDetailScreen(
    collectionId: SmartCollectionId,
    onDone: () -> Unit,
    onOpenGameDetail: (Long) -> Unit,
    viewModel: SmartCollectionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val collection = state.smartCollections.firstOrNull { it.id == collectionId }
    var density by rememberSaveable { mutableStateOf(state.collectionDensity) }

    BackHandler(onBack = onDone)
    androidx.compose.runtime.LaunchedEffect(state.collectionDensity) {
        density = state.collectionDensity
    }

    if (state.loading || collection == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (state.loading) CircularProgressIndicator() else Text("Collection unavailable")
        }
        return
    }

    val members = collection.members.map { member ->
        CollectionMemberUi(
            appId = member.appId,
            name = member.name,
            iconUrl = member.iconUrl.takeIf { it.isNotBlank() },
            headerUrl = member.headerUrl,
            heroCapsuleUrl = member.heroCapsuleUrl,
            playtimeMinutes = member.playtimeMinutes,
            achievementsUnlocked = member.achievementsUnlocked,
            achievementsTotal = member.achievementsTotal,
            sessionCount = member.sessionCount,
            completionistMinutes = member.completionistMinutes,
            mainStoryMinutes = member.mainStoryMinutes,
            mainExtraMinutes = member.mainExtraMinutes,
            allStylesMinutes = member.allStylesMinutes,
            completionBasis = member.completionBasis,
            isFamilyShared = member.isFamilyShared,
            isCurrentlyPlaying = member.isCurrentlyPlaying,
        )
    }

    val collectionAccent = MaterialTheme.colorScheme.primary

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDone) {
                Icon(TablerIcons.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = collection.name,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(collection.rule, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "${members.size} ${if (members.size == 1) "game" else "games"}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = SMART_COLLECTION_MISSING_HLTB_NOTE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Games", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    GameListDensityControl(
                        density = density,
                        onDensityChange = {
                            density = it
                            viewModel.setDensity(it)
                        },
                    )
                }
            }
            collectionMemberItems(
                members = members,
                density = density,
                accentColor = collectionAccent,
                showQueuePosition = false,
                onOpenGameDetail = onOpenGameDetail,
            )
            item { Spacer(Modifier.size(16.dp)) }
        }
    }
}
