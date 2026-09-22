package com.example.backlogium.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.backlogium.ui.components.GameIcon
import compose.icons.TablerIcons
import compose.icons.tablericons.DeviceGamepad
import androidx.compose.material3.Icon

internal const val HOME_NEXT_ACTION_TAG = "home-next-action"
internal const val HOME_NEXT_ACTION_PRIMARY_TAG = "home-next-action-primary"
internal const val HOME_NEXT_ACTION_COLLECTION_TAG = "home-next-action-collection"

/** Compact, single-choice presentation for the action selected by the Home ViewModel. */
@Composable
internal fun HomeNextActionSurface(
    action: HomeNextAction,
    onOpenGame: (Long) -> Unit,
    onOpenCollection: (Long) -> Unit,
    onOpenLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(HOME_NEXT_ACTION_TAG),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Next action",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            when (action) {
                is HomeNextAction.ContinueFocus -> {
                    ActionGameSummary(
                        eyebrow = "Continue Focus",
                        game = action.game,
                    )
                    Button(
                        onClick = { onOpenGame(action.game.appId) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(HOME_NEXT_ACTION_PRIMARY_TAG),
                    ) {
                        Text("Open game")
                    }
                }

                is HomeNextAction.ContinueCollection -> {
                    ActionGameSummary(
                        eyebrow = "Continue ${action.collectionName}",
                        game = action.game,
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Button(
                            onClick = { onOpenGame(action.game.appId) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(HOME_NEXT_ACTION_PRIMARY_TAG),
                        ) {
                            Text("Play next")
                        }
                        TextButton(
                            onClick = { onOpenCollection(action.collectionId) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(HOME_NEXT_ACTION_COLLECTION_TAG),
                        ) {
                            Text("Open collection")
                        }
                    }
                }

                HomeNextAction.ChooseGame -> {
                    Text(
                        text = "Choose a game from your Library.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = onOpenLibrary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(HOME_NEXT_ACTION_PRIMARY_TAG),
                    ) {
                        Text("Browse Library")
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionGameSummary(
    eyebrow: String,
    game: HomeNextGame,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        if (game.iconUrl != null) {
            GameIcon(
                iconUrl = game.iconUrl,
                modifier = Modifier.size(48.dp),
                iconSize = 48.dp,
            )
        } else {
            Icon(
                imageVector = TablerIcons.DeviceGamepad,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = eyebrow,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = game.name,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
