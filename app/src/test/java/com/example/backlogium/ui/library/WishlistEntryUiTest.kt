package com.example.backlogium.ui.library

import com.example.backlogium.data.repo.WishlistGame
import com.example.backlogium.data.repo.WishlistPrice
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The four price states the row has to keep apart. Three of them carry no amount at all, and the
 * spec is explicit that none may be rendered as a zero, a dash, or a blank — which starts here,
 * with them being different types rather than a nullable string.
 */
class WishlistEntryUiTest {

    @Test fun aFreshPriceIsCurrent_andCarriesNoDate() {
        val price = WishlistPrice.Observed("P2,099.00", null, 0, OBSERVED_AT, current = true).toUi()

        assertEquals(WishlistPriceUi.Current("P2,099.00", null, 0), price)
    }

    @Test fun anOlderPriceIsRetained_andKeepsTheDateItWasObserved() {
        val price = WishlistPrice.Observed("P2,099.00", null, 0, OBSERVED_AT, current = false).toUi()

        assertEquals(
            WishlistPriceUi.Retained("P2,099.00", null, 0, OBSERVED_AT),
            price,
        )
    }

    @Test fun aDiscountKeepsBothPricesAndThePercentage() {
        val price = WishlistPrice.Observed("P849.75", "P3,399.00", 75, OBSERVED_AT, current = true).toUi()

        assertEquals(WishlistPriceUi.Current("P849.75", "P3,399.00", 75), price)
    }

    @Test fun aRecordedAbsenceAndNoObservationAtAll_areDifferentStates() {
        // "Steam says this has no price" and "this has never been looked up" are different
        // claims, and collapsing them would have the app assert one it has not established.
        assertEquals(WishlistPriceUi.Unavailable, WishlistPrice.Unavailable(OBSERVED_AT).toUi())
        assertEquals(WishlistPriceUi.NeverObserved, WishlistPrice.NeverObserved.toUi())
    }

    @Test fun theStoreLinkSurvivesEveryPriceState() {
        val entry = WishlistGame(
            appId = 292030,
            name = "The Witcher 3",
            artworkUrl = "https://cdn/292030.jpg",
            priority = 1,
            addedAt = 0,
            price = WishlistPrice.NeverObserved,
            storeUrl = "https://store.steampowered.com/app/292030",
        ).toUi()

        assertEquals("https://store.steampowered.com/app/292030", entry.storeUrl)
        assertEquals(WishlistPriceUi.NeverObserved, entry.price)
    }

    private companion object {
        const val OBSERVED_AT = 1_800_000_000_000L
    }
}
