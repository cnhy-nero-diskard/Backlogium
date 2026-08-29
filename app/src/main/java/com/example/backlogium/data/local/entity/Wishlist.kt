package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One game on the player's Steam wishlist.
 *
 * **Deliberately not a row in `games`.** A wishlisted game is not owned, has no sessions, and must
 * contribute to no library count, XP denominator, completion figure, or analytic. Giving it a
 * `games` row — even behind a source flag — would place it in front of every existing query and
 * make each one responsible for excluding it. A want is not a have.
 *
 * For the same reason there is no foreign key to `games`: the app id is one the player does *not*
 * own, so no parent row exists to point at. That is also why [name] and [artworkUrl] are stored
 * here rather than read from a game row, and why `GameGenreCache` — which does carry that key —
 * cannot be reused for wishlist enrichment.
 *
 * [lastSeenAt] records when the entry was last present in a wishlist Steam actually answered with,
 * so an entry can be aged out on a successful read without a failed one erasing it.
 */
@Entity(tableName = "wishlist_items")
data class WishlistItem(
    @PrimaryKey val appId: Long,
    val name: String,
    val artworkUrl: String,
    /** The player's own ordering in Steam. 0 means unprioritized, not "first". */
    val priority: Int,
    val addedAt: Long,
    val lastSeenAt: Long,
)

/**
 * One price observation for one wishlisted app, appended and never updated.
 *
 * History is cheap to accumulate — a row per app per observation — and impossible to reconstruct
 * afterwards, which is the whole argument for recording it before anything consumes it. Steam's
 * own wishlist only ever compares against list price, so "is this actually the lowest it has been?"
 * is the question this table exists to be able to answer later.
 *
 * A *successful* observation of "no price" is recorded too, with the price columns null: an app
 * being free, unreleased, or unsold in the region is a fact about that moment, and a later
 * observation carrying a price is how a release shows up. A *failed* lookup records nothing at
 * all, so the previous observation and its date stand.
 */
@Entity(
    tableName = "wishlist_price_observations",
    indices = [Index(value = ["appId", "observedAt"])],
)
data class WishlistPriceObservation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val appId: Long,
    val observedAt: Long,
    /** Null together with every other price column when Steam reported no price. */
    val currency: String? = null,
    val finalMinorUnits: Long? = null,
    val initialMinorUnits: Long? = null,
    val discountPercent: Int? = null,
    /** Steam's own rendering for the region — the only form safe to display. */
    val formatted: String? = null,
    /** The struck-through list price, present only while a discount is active. */
    val listFormatted: String? = null,
)
