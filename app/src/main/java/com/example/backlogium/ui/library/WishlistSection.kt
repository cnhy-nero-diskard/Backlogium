package com.example.backlogium.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.example.backlogium.data.remote.SteamIconMapper
import com.example.backlogium.data.repo.WishlistAvailability
import com.example.backlogium.ui.components.GameHeaderBackdrop
import com.example.backlogium.ui.util.UiFormat
import compose.icons.TablerIcons
import compose.icons.tablericons.ChevronDown
import compose.icons.tablericons.ChevronUp
import compose.icons.tablericons.ExternalLink

/**
 * The wishlist, as a section of the Library.
 *
 * Collapsed by default and placed above the owned lists, which is what lets it be reachable
 * without displacing them: a wishlist of any size costs one header row until the player asks for
 * it, and the owned lists keep the position, sorting, grouping, density, and search they have
 * today. Expanding is also the "opened" event the refresh policy hangs off.
 */
fun LazyListScope.wishlistSection(
    state: WishlistUiState,
    onToggle: (Boolean) -> Unit,
    onOpenStore: (WishlistEntryUi) -> Unit,
) {
    if (!state.configured) return

    item {
        WishlistSectionHeader(
            expanded = state.expanded,
            count = state.entries.size,
            refreshing = state.refreshing,
            onToggle = { onToggle(!state.expanded) },
        )
    }

    if (!state.expanded) return

    if (state.staleNotice) {
        item { WishlistNotice(state.availability, hasEntries = true) }
    }

    if (state.entries.isEmpty()) {
        item {
            when {
                state.isEmpty -> WishlistMessage(
                    title = "Nothing wishlisted",
                    message = "Games you add to your Steam wishlist show up here.",
                )

                state.availability == WishlistAvailability.NOT_READABLE ||
                    state.availability == WishlistAvailability.UNREACHABLE ->
                    WishlistNotice(state.availability, hasEntries = false)

                state.refreshing -> WishlistMessage(
                    title = "Checking your wishlist",
                    message = "Reading it from Steam.",
                )

                else -> WishlistMessage(
                    title = "Wishlist not loaded yet",
                    message = "It is read from Steam when this section is opened.",
                )
            }
        }
        return
    }

    items(count = state.entries.size, key = { "wishlist-${state.entries[it].appId}" }) { index ->
        WishlistEntryRow(entry = state.entries[index], onOpenStore = onOpenStore)
    }
}

@Composable
private fun WishlistSectionHeader(
    expanded: Boolean,
    count: Int,
    refreshing: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Wishlist",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onToggle) {
            Text(
                text = when {
                    refreshing -> "Checking…"
                    // The count is only meaningful once something has been read; a bare "0"
                    // before the first read would assert an empty wishlist that is not known yet.
                    expanded || count > 0 -> if (count > 0) "$count wanted" else "Show"
                    else -> "Show"
                },
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) TablerIcons.ChevronUp else TablerIcons.ChevronDown,
                contentDescription = if (expanded) "Hide wishlist" else "Show wishlist",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * One wanted game. The whole row is the store link, so the link is offered whatever the price
 * state turns out to be.
 */
@Composable
private fun WishlistEntryRow(entry: WishlistEntryUi, onOpenStore: (WishlistEntryUi) -> Unit) {
    Card(
        onClick = { onOpenStore(entry) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics { contentDescription = "Open ${entry.name} on Steam" },
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            GameHeaderBackdrop(
                headerUrl = entry.artworkUrl,
                fallbackUrls = SteamIconMapper.listBackgroundFallbackUrls(entry.appId),
                modifier = Modifier.matchParentSize(),
            )
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = entry.name, style = MaterialTheme.typography.bodyLarge)
                    WantedLabel()
                    Spacer(Modifier.padding(top = 2.dp))
                    WishlistPriceLabel(entry.price)
                }
                Icon(
                    imageVector = TablerIcons.ExternalLink,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * What separates a wanted game from an owned one, in words. Colour alone would not survive a
 * colour-blind reader or a greyscale screenshot, and these rows sit in the same list as games the
 * player actually has — mistaking the two is the one error this section must not invite.
 */
@Composable
private fun WantedLabel() {
    Text(
        text = "Wishlisted · not owned",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The price, in whichever of its four states it is in.
 *
 * None of the three non-price states renders anything that could be read as an amount: no zero,
 * no dash, no blank line where a number would sit.
 */
@Composable
private fun WishlistPriceLabel(price: WishlistPriceUi) {
    when (price) {
        is WishlistPriceUi.Current -> PriceAmount(
            formatted = price.formatted,
            listFormatted = price.listFormatted,
            discountPercent = price.discountPercent,
            observedNote = null,
        )

        is WishlistPriceUi.Retained -> PriceAmount(
            formatted = price.formatted,
            listFormatted = price.listFormatted,
            discountPercent = price.discountPercent,
            // A retained price is never presented as today's. The date is the whole point.
            observedNote = "Seen ${UiFormat.date(price.observedAt)}",
        )

        WishlistPriceUi.Unavailable -> Text(
            text = "No price available",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        WishlistPriceUi.NeverObserved -> Text(
            text = "Price not checked yet",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PriceAmount(
    formatted: String,
    listFormatted: String?,
    discountPercent: Int,
    observedNote: String?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = formatted,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
        if (discountPercent > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "-$discountPercent%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary,
            )
            if (listFormatted != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = listFormatted,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = TextDecoration.LineThrough,
                )
            }
        }
    }
    if (observedNote != null) {
        Text(
            text = observedNote,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Why the section could not be refreshed. An unreadable wishlist and an unreachable Steam are
 * different problems with different fixes, and neither may be left to look like an empty wishlist.
 */
@Composable
private fun WishlistNotice(availability: WishlistAvailability, hasEntries: Boolean) {
    val message = when (availability) {
        WishlistAvailability.NOT_READABLE ->
            "Steam will not share this wishlist. It is private, or its profile is."

        WishlistAvailability.UNREACHABLE ->
            "Steam could not be reached."

        WishlistAvailability.AVAILABLE, WishlistAvailability.UNKNOWN -> return
    }
    if (hasEntries) {
        Text(
            text = "$message Showing what was last seen.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    } else {
        WishlistMessage(title = "Wishlist unavailable", message = message)
    }
}

@Composable
private fun WishlistMessage(title: String, message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
