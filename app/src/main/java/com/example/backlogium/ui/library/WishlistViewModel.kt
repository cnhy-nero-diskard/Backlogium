package com.example.backlogium.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.repo.CredentialsRepository
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.WishlistAvailability
import com.example.backlogium.data.repo.WishlistGame
import com.example.backlogium.data.repo.WishlistPrice
import com.example.backlogium.data.repo.WishlistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * How a wishlist entry's price should read. The four cases are separate types rather than a
 * nullable string because none of them may be rendered as a zero, a dash, or a blank — each of
 * which the eye reads as a price.
 */
sealed interface WishlistPriceUi {

    /** Observed inside the freshness window: shown plainly, with no qualification. */
    data class Current(
        val formatted: String,
        val listFormatted: String?,
        val discountPercent: Int,
    ) : WishlistPriceUi

    /** Observed longer ago than that, so it is shown *with the date it was observed*. */
    data class Retained(
        val formatted: String,
        val listFormatted: String?,
        val discountPercent: Int,
        val observedAt: Long,
    ) : WishlistPriceUi

    /** Steam answered and this app has no price. The app does not guess at why. */
    data object Unavailable : WishlistPriceUi

    /** Never successfully looked up. Distinct from "no price": nothing is claimed either way. */
    data object NeverObserved : WishlistPriceUi
}

/** One wishlist entry, already resolved to what the row draws. */
data class WishlistEntryUi(
    val appId: Long,
    val name: String,
    val artworkUrl: String,
    val price: WishlistPriceUi,
    val storeUrl: String,
)

data class WishlistUiState(
    /** False hides the section outright: with no Steam account there is no wishlist to speak of. */
    val configured: Boolean = false,
    val expanded: Boolean = false,
    val refreshing: Boolean = false,
    val entries: List<WishlistEntryUi> = emptyList(),
    val availability: WishlistAvailability = WishlistAvailability.UNKNOWN,
) {
    /** True only once a read has established there is genuinely nothing wishlisted. */
    val isEmpty: Boolean
        get() = entries.isEmpty() && availability == WishlistAvailability.AVAILABLE

    /**
     * True when entries are on screen but the last read failed. The section keeps showing them —
     * with their dated prices — and says separately that it could not refresh, rather than
     * choosing between a stale list and an empty one.
     */
    val staleNotice: Boolean
        get() = entries.isNotEmpty() && availability != WishlistAvailability.AVAILABLE &&
            availability != WishlistAvailability.UNKNOWN
}

/**
 * The wishlist section's own state, kept apart from [LibraryViewModel] so nothing about wanting a
 * game can reach the owned library's sorting, grouping, filtering, or selection.
 *
 * The section is collapsed until the player opens it, and opening it is what triggers a refresh —
 * the freshness window then keeps repeated opening from re-requesting.
 */
@HiltViewModel
class WishlistViewModel @Inject constructor(
    private val repository: WishlistRepository,
    credentials: CredentialsRepository,
) : ViewModel() {

    private val expanded = MutableStateFlow(false)
    private val refreshing = MutableStateFlow(false)

    val uiState: StateFlow<WishlistUiState> = combine(
        credentials.credentialsStateFlow,
        repository.wishlist,
        repository.availability,
        expanded,
        refreshing,
    ) { credentialsState, games, availability, isExpanded, isRefreshing ->
        WishlistUiState(
            configured = credentialsState is CredentialsState.Configured,
            expanded = isExpanded,
            refreshing = isRefreshing,
            entries = games.map(WishlistGame::toUi),
            availability = availability,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WishlistUiState())

    /** Opening the section is the refresh trigger; closing it is not, and cancels nothing. */
    fun setExpanded(value: Boolean) {
        expanded.value = value
        if (value) refresh()
    }

    private fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            try {
                repository.refresh()
            } finally {
                refreshing.value = false
            }
        }
    }
}

/**
 * The row model for one wanted game. Internal rather than private so the four price states can be
 * pinned by a test — they are the part of this screen most easily got wrong, and the one thing the
 * spec is explicit about is that none of them may read as an amount.
 */
internal fun WishlistGame.toUi() = WishlistEntryUi(
    appId = appId,
    name = name,
    artworkUrl = artworkUrl,
    price = price.toUi(),
    storeUrl = storeUrl,
)

internal fun WishlistPrice.toUi(): WishlistPriceUi = when (this) {
    is WishlistPrice.Observed -> if (current) {
        WishlistPriceUi.Current(formatted, listFormatted, discountPercent)
    } else {
        WishlistPriceUi.Retained(formatted, listFormatted, discountPercent, observedAt)
    }

    is WishlistPrice.Unavailable -> WishlistPriceUi.Unavailable
    WishlistPrice.NeverObserved -> WishlistPriceUi.NeverObserved
}
