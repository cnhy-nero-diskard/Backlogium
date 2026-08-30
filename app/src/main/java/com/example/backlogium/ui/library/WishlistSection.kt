package com.example.backlogium.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.backlogium.data.remote.SteamIconMapper
import com.example.backlogium.data.repo.WishlistAvailability
import com.example.backlogium.domain.GameListDensity
import com.example.backlogium.ui.components.GameHeaderBackdrop
import com.example.backlogium.ui.components.GameHeroCapsule
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
 *
 * Entries follow the Library's own density control, so switching to grid does not leave a list of
 * wanted games sitting under a grid of owned ones.
 *
 * What drops as the grid tightens is the **wishlisted label**, not the price. The price is why
 * this section exists, and at three columns it also does the label's job: an owned tile at that
 * density carries a name and nothing else, so a money capsule — or the words "No price available"
 * — separates a want from a have by structure rather than by colour. Repeating the word
 * "Wishlisted" under every third tile only crowds out the figure the player came to read.
 */
fun LazyListScope.wishlistSection(
    state: WishlistUiState,
    density: GameListDensity,
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

    if (!density.isGrid) {
        state.entries.forEach { entry ->
            item(key = "wishlist-${entry.appId}") {
                WishlistEntryRow(entry = entry, onOpenStore = onOpenStore)
            }
        }
        return
    }

    state.entries.chunked(density.columns).forEachIndexed { rowIndex, row ->
        item(key = "wishlist-grid-row-$rowIndex-${row.firstOrNull()?.appId ?: 0}") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { entry ->
                    WishlistEntryCell(
                        entry = entry,
                        density = density,
                        onOpenStore = onOpenStore,
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(density.columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
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
                    count > 0 -> "$count wanted"
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
 * One wanted game as a list row. The whole row is the store link, so the link is offered whatever
 * the price state turns out to be.
 */
@Composable
private fun WishlistEntryRow(
    entry: WishlistEntryUi,
    onOpenStore: (WishlistEntryUi) -> Unit,
) {
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
                    WantedLabel(modifier = Modifier.padding(top = 2.dp))
                    WishlistPrice(
                        price = entry.price,
                        modifier = Modifier.padding(top = 6.dp),
                    )
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
 * One wanted game as a grid tile, sharing the owned cell's shell — same portrait hero capsule,
 * same shape, same proportions — so the two grids read as one surface rather than two designs.
 * The wishlisted marking and the price capsule are what tell them apart.
 */
@Composable
private fun WishlistEntryCell(
    entry: WishlistEntryUi,
    density: GameListDensity,
    onOpenStore: (WishlistEntryUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val compact = density == GameListDensity.COMPACT_GRID
    val tileShape = RoundedCornerShape(18.dp)
    val heroShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
    Card(
        onClick = { onOpenStore(entry) },
        modifier = modifier
            .padding(vertical = 4.dp)
            .aspectRatio(if (compact) 0.62f else 0.56f)
            .semantics { contentDescription = "Open ${entry.name} on Steam" },
        shape = tileShape,
        elevation = CardDefaults.cardElevation(defaultElevation = if (compact) 1.dp else 2.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(heroShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                GameHeroCapsule(
                    heroCapsuleUrl = SteamIconMapper.heroCapsuleUrl(entry.appId),
                    fallbackUrls = SteamIconMapper.gridArtworkFallbackUrls(entry.appId),
                    modifier = Modifier.matchParentSize(),
                    shape = heroShape,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (compact) 9.dp else 12.dp, vertical = 9.dp),
                horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.Start,
            ) {
                Text(
                    text = entry.name,
                    style = if (compact) {
                        MaterialTheme.typography.labelLarge
                    } else {
                        MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                    textAlign = if (compact) TextAlign.Center else TextAlign.Start,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                // At three columns the repeated "Wishlisted" is noise, and the price line takes
                // over marking the tile: an owned tile at this density carries a name and nothing
                // else, so a money capsule — or the words "No price available" — is a difference
                // in structure rather than in colour. Removing the price here would leave these
                // tiles indistinguishable from owned ones, which is the thing to not do.
                if (!compact) WantedLabel(modifier = Modifier.padding(top = 3.dp))
                WishlistPrice(
                    price = entry.price,
                    compact = compact,
                    modifier = Modifier.padding(top = if (compact) 4.dp else 5.dp),
                )
            }
        }
    }
}

/**
 * What separates a wanted game from an owned one, in words. Colour alone would not survive a
 * colour-blind reader or a greyscale screenshot, and these tiles are the same shell as the owned
 * ones directly below — mistaking a want for a have is the one error this section must not invite.
 * That makes this identity rather than detail, so it survives every density.
 */
@Composable
private fun WantedLabel(modifier: Modifier = Modifier) {
    Text(
        text = "Wishlisted",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { contentDescription = "Wishlisted, not owned" },
    )
}

/**
 * The price, in whichever of its four states it is in.
 *
 * **The capsule means money.** Only an actual amount is enclosed and tinted; the two states that
 * carry no amount stay plain, quiet text. Wrapping "No price available" in the same pill would
 * give an absence the exact visual weight of a price, which is what the spec forbids in the same
 * breath as rendering it as a zero or a dash.
 */
@Composable
private fun WishlistPrice(
    price: WishlistPriceUi,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    when (price) {
        is WishlistPriceUi.Current -> PriceCapsule(
            formatted = price.formatted,
            listFormatted = price.listFormatted,
            discountPercent = price.discountPercent,
            observedNote = null,
            compact = compact,
            modifier = modifier,
        )

        is WishlistPriceUi.Retained -> PriceCapsule(
            formatted = price.formatted,
            listFormatted = price.listFormatted,
            discountPercent = price.discountPercent,
            // A retained price is never presented as today's. The date is the whole point, and
            // it survives the compact tile for that reason — dropping it to save a line would
            // turn a remembered price into a claim about the price right now.
            observedNote = "Seen ${UiFormat.date(price.observedAt)}",
            compact = compact,
            modifier = modifier,
        )

        WishlistPriceUi.Unavailable -> AbsentPrice("No price available", modifier)
        WishlistPriceUi.NeverObserved -> AbsentPrice("Price not checked yet", modifier)
    }
}

@Composable
private fun AbsentPrice(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * A price, enclosed so it reads as a figure rather than as another line of the row's prose.
 *
 * A discount takes the tertiary fill, so a sale is visible from across the list without the app
 * having to shout. At the two roomier densities it also carries its percentage inside the capsule
 * and the struck-through list price outside it — context for the amount, not a second amount.
 *
 * At three columns both of those go and the fill carries the signal alone. That is a real
 * reduction: a reader who cannot separate the two fills loses the discount at this density, where
 * at every other one it is spelled out. It is the same bargain the compact grid already makes with
 * playtime and achievements — the densest tier trades detail for count — and the price itself,
 * which is what the sale actually amounts to, never leaves the tile.
 */
@Composable
private fun PriceCapsule(
    formatted: String,
    listFormatted: String?,
    discountPercent: Int,
    observedNote: String?,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val discounted = discountPercent > 0
    // Spoken even where the percentage is not drawn, so the compact tile's fill is not the only
    // way the discount exists.
    val spoken = if (discounted) "$formatted, $discountPercent% off" else formatted
    val container = if (discounted) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val onContainer = if (discounted) {
        MaterialTheme.colorScheme.onTertiary
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(container)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .semantics(mergeDescendants = true) { contentDescription = spoken },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (discounted && !compact) {
                    Text(
                        text = "-$discountPercent%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = onContainer,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = formatted,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = onContainer,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            // The struck-through list price is context for the amount, not a second amount, so
            // it is the first thing to go where three columns leave no room: the capsule still
            // carries both the discount and what the game actually costs.
            if (discounted && listFormatted != null && !compact) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = listFormatted,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = TextDecoration.LineThrough,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (observedNote != null) {
            Text(
                text = observedNote,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
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
