package com.example.backlogium.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.backlogium.R
import com.example.backlogium.ui.components.GameIcon
import compose.icons.TablerIcons
import compose.icons.tablericons.DeviceGamepad

internal const val HOME_NEXT_ACTION_TAG = "home-next-action"
internal const val HOME_NEXT_ACTION_PRIMARY_TAG = "home-next-action-primary"
internal const val HOME_NEXT_ACTION_COLLECTION_TAG = "home-next-action-collection"

/** Keeps the suggestion out of the way while the dedicated now-playing panel owns Home. */
@Composable
internal fun HomeNextActionSlot(
    isInGame: Boolean,
    action: HomeNextAction,
    onOpenGame: (Long) -> Unit,
    onOpenCollection: (Long) -> Unit,
    onOpenLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!isInGame) {
        HomeNextActionSurface(
            action = action,
            onOpenGame = onOpenGame,
            onOpenCollection = onOpenCollection,
            onOpenLibrary = onOpenLibrary,
            modifier = modifier,
        )
    }
}

/** A lightweight row for the single action selected by the Home ViewModel. */
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
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        when (action) {
            is HomeNextAction.ContinueFocus -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ActionGameSummary(
                        eyebrow = stringResource(R.string.home_continue_focus),
                        game = action.game,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = { onOpenGame(action.game.appId) },
                        contentPadding = compactButtonPadding,
                        modifier = Modifier.testTag(HOME_NEXT_ACTION_PRIMARY_TAG),
                    ) {
                        Text(stringResource(R.string.home_open_game))
                    }
                }
            }

            is HomeNextAction.ContinueCollection -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ActionGameSummary(
                        eyebrow = stringResource(
                            R.string.home_continue_collection,
                            action.collectionName,
                        ),
                        game = action.game,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { onOpenCollection(action.collectionId) },
                            modifier = Modifier.testTag(HOME_NEXT_ACTION_COLLECTION_TAG),
                        ) {
                            Text(stringResource(R.string.home_open_collection))
                        }
                        Button(
                            onClick = { onOpenGame(action.game.appId) },
                            contentPadding = compactButtonPadding,
                            modifier = Modifier.testTag(HOME_NEXT_ACTION_PRIMARY_TAG),
                        ) {
                            Text(stringResource(R.string.home_play_next))
                        }
                    }
                }
            }

            HomeNextAction.ChooseGame -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.home_choose_game),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = onOpenLibrary,
                        contentPadding = compactButtonPadding,
                        modifier = Modifier.testTag(HOME_NEXT_ACTION_PRIMARY_TAG),
                    ) {
                        Text(stringResource(R.string.home_browse_library))
                    }
                }
            }
        }
    }
}

private val compactButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)

@Composable
private fun ActionGameSummary(
    eyebrow: String,
    game: HomeNextGame,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (game.iconUrl != null) {
            GameIcon(
                iconUrl = game.iconUrl,
                modifier = Modifier.size(36.dp),
                iconSize = 36.dp,
            )
        } else {
            Icon(
                imageVector = TablerIcons.DeviceGamepad,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = eyebrow,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = game.name ?: stringResource(R.string.home_game_fallback, game.appId),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
